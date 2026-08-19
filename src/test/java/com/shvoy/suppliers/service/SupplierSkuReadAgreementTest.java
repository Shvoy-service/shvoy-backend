package com.shvoy.suppliers.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.shvoy.TenantContext;
import com.shvoy.suppliers.dto.PriceResolutionResult;
import com.shvoy.suppliers.dto.SupplierSkuView;

/**
 * The tripwire for the story's single-source-of-truth constraint: the
 * supplier SKU read ({@link SkuService#listSkus}) and the price-resolution
 * service ({@link PriceResolutionService}) must never disagree about a SKU's
 * price state. Both derive "the current/effective price" from the same
 * {@code SkuPriceSelection}; if that ever forks, one of these four cases
 * breaks.
 *
 * <p>The agreement asserted, for a SKU as of today:
 * <ul>
 *   <li>{@code currentPrice == null} in the read ⟺ the SKU was never priced
 *       ({@code !everPriced} in resolution);</li>
 *   <li>{@code currentPrice.inDate} in the read ⟺ resolution finds a valid
 *       price today ({@code priceFound});</li>
 *   <li>when a price is in-date, both pick the <em>same</em> row
 *       ({@code currentPrice.id == skuPriceId}).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class SupplierSkuReadAgreementTest {

    @Autowired
    SkuService skuService;

    @Autowired
    PriceResolutionService priceResolutionService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    UUID supplierAId;

    @BeforeEach
    void seedCompanyAndSupplier() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        supplierAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierAId, "Supplier A", now, companyA);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM discount_tiers WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM sku_prices WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM companies WHERE id = ?", companyA);
    }

    private UUID seedSku(String code) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, status, created_at, company_id) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
            id, supplierAId, code, Timestamp.from(Instant.now()), companyA);
        return id;
    }

    private void seedPrice(UUID skuId, String amount, LocalDate validFrom, LocalDate validTo) {
        jdbcTemplate.update(
            "INSERT INTO sku_prices (id, sku_id, unit_price_amount, currency, valid_from, valid_to, created_at, company_id) "
                + "VALUES (?, ?, ?, 'USD', ?, ?, ?, ?)",
            UUID.randomUUID(), skuId, new BigDecimal(amount), Date.valueOf(validFrom),
            validTo == null ? null : Date.valueOf(validTo), Timestamp.from(Instant.now()), companyA);
    }

    /**
     * Runs both surfaces for {@code skuId} and asserts they agree about its
     * price state today.
     */
    private void assertReadAgreesWithResolution(UUID skuId) {
        TenantContext.set(companyA);
        try {
            SupplierSkuView view = skuService.listSkus(supplierAId).stream()
                .filter(v -> v.sku().id().equals(skuId))
                .findFirst()
                .orElseThrow();
            PriceResolutionResult resolved =
                priceResolutionService.resolve(supplierAId, skuId, 1, LocalDate.now());

            assertThat(view.currentPrice() == null)
                .as("read's null current price ⟺ resolution says never priced")
                .isEqualTo(!resolved.everPriced());

            if (view.currentPrice() != null) {
                assertThat(view.currentPrice().inDate())
                    .as("read's inDate flag ⟺ resolution finds a valid price today")
                    .isEqualTo(resolved.priceFound());
            }

            if (resolved.priceFound()) {
                assertThat(view.currentPrice().id())
                    .as("both surfaces resolve to the same SkuPrice row")
                    .isEqualTo(resolved.skuPriceId());
            }
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void agreesOnAnOpenInDateRow() {
        UUID skuId = seedSku("SKU-OPEN");
        seedPrice(skuId, "2.0000", LocalDate.now().minusMonths(1), null);

        assertReadAgreesWithResolution(skuId);
    }

    @Test
    void agreesOnABoundedInDateRow() {
        UUID skuId = seedSku("SKU-BOUNDED");
        seedPrice(skuId, "2.0000", LocalDate.now().minusDays(10), LocalDate.now().plusDays(10));

        assertReadAgreesWithResolution(skuId);
    }

    @Test
    void agreesOnAnExpiredNewestRow() {
        UUID skuId = seedSku("SKU-EXPIRED");
        // Two windows, both expired; the newest (latest validFrom) is the current one, and it's out of date.
        seedPrice(skuId, "1.0000", LocalDate.now().minusMonths(6), LocalDate.now().minusMonths(3).minusDays(1));
        seedPrice(skuId, "2.0000", LocalDate.now().minusMonths(3), LocalDate.now().minusDays(1));

        assertReadAgreesWithResolution(skuId);
    }

    @Test
    void agreesOnANeverPricedSku() {
        UUID skuId = seedSku("SKU-UNPRICED");

        assertReadAgreesWithResolution(skuId);
    }
}
