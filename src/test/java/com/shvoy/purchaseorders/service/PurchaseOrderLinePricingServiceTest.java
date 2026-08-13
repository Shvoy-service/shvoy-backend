package com.shvoy.purchaseorders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.shvoy.ConflictException;
import com.shvoy.NotFoundException;
import com.shvoy.TenantContext;
import com.shvoy.ValidationException;
import com.shvoy.purchaseorders.domain.PurchaseOrderLine;
import com.shvoy.purchaseorders.repository.PurchaseOrderLineRepository;

/**
 * No class-level @Transactional — see SupplierTenantIsolationTest's
 * Javadoc for why; seeds via raw JDBC and sets TenantContext explicitly
 * around every call, same as PoNumberGeneratorTest (priceLine is
 * @Transactional against this app's JPA-backed transaction manager, which
 * needs a resolvable tenant even though this service itself never touches
 * JPA directly — see the tenancy-gotchas memory note from that story).
 */
@SpringBootTest
@ActiveProfiles("test")
class PurchaseOrderLinePricingServiceTest {

    @Autowired
    PurchaseOrderLinePricingService purchaseOrderLinePricingService;

    @Autowired
    PurchaseOrderLineRepository purchaseOrderLineRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID supplierAId;
    UUID otherSupplierId;
    UUID userAId;
    UUID poAId;
    UUID poBId;

    @BeforeEach
    void seedCompanySupplierUserAndPo() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);

        supplierAId = UUID.randomUUID();
        otherSupplierId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierAId, "Supplier A", now, companyA);
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            otherSupplierId, "Other Supplier", now, companyA);

        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "admin-a@example.com", now, companyA);

        poAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, 'PO-0001', 'DRAFT', ?, ?, ?)",
            poAId, supplierAId, userAId, now, companyA);

        poBId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, 'PO-0001', 'DRAFT', ?, ?, ?)",
            poBId, supplierAId, userAId, now, companyB);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM discount_tiers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM sku_prices WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private UUID seedSku(UUID supplierId, Integer cartonSize) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, status, carton_size, created_at, company_id) "
                + "VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)",
            id, supplierId, "SKU-" + id, cartonSize, Timestamp.from(Instant.now()), companyA);
        return id;
    }

    private UUID seedPrice(UUID skuId, String amount, String currency, LocalDate validFrom, LocalDate validTo) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO sku_prices (id, sku_id, unit_price_amount, currency, valid_from, valid_to, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, skuId, new BigDecimal(amount), currency, Date.valueOf(validFrom),
            validTo == null ? null : Date.valueOf(validTo), Timestamp.from(Instant.now()), companyA);
        return id;
    }

    private void seedTier(UUID priceId, int threshold, String amount) {
        jdbcTemplate.update(
            "INSERT INTO discount_tiers (id, sku_price_id, quantity_threshold, unit_price_amount, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), priceId, threshold, new BigDecimal(amount), Timestamp.from(Instant.now()), companyA);
    }

    private PurchaseOrderLine createLine(UUID poId, UUID skuId, int quantity) {
        TenantContext.set(companyA);
        try {
            return purchaseOrderLineRepository.save(new PurchaseOrderLine(poId, skuId, 1, quantity));
        } finally {
            TenantContext.clear();
        }
    }

    private PurchaseOrderLine priceLine(PurchaseOrderLine line) {
        TenantContext.set(companyA);
        try {
            return purchaseOrderLinePricingService.priceLine(line);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void resolvesLineWithTierApplied() {
        UUID skuId = seedSku(supplierAId, null);
        UUID priceId = seedPrice(skuId, "2.0000", "GBP", LocalDate.of(2026, 1, 1), null);
        seedTier(priceId, 100, "1.5000");
        PurchaseOrderLine line = createLine(poAId, skuId, 150);

        PurchaseOrderLine priced = priceLine(line);

        assertThat(priced.getPriceFound()).isTrue();
        assertThat(priced.getUnitPrice().amount()).isEqualByComparingTo("1.5000");
        assertThat(priced.getUnitPrice().currency()).isEqualTo("GBP");
        assertThat(priced.getAppliedTierThreshold()).isEqualTo(100);
        assertThat(priced.getPricedAsOfDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void resolvesBasePriceBelowAllTiers() {
        UUID skuId = seedSku(supplierAId, null);
        UUID priceId = seedPrice(skuId, "2.0000", "GBP", LocalDate.of(2026, 1, 1), null);
        seedTier(priceId, 100, "1.5000");
        PurchaseOrderLine line = createLine(poAId, skuId, 50);

        PurchaseOrderLine priced = priceLine(line);

        assertThat(priced.getPriceFound()).isTrue();
        assertThat(priced.getUnitPrice().amount()).isEqualByComparingTo("2.0000");
        assertThat(priced.getAppliedTierThreshold()).isNull();
    }

    @Test
    void surfacesCartonAdjustmentForANonMultipleQuantity() {
        UUID skuId = seedSku(supplierAId, 10);
        seedPrice(skuId, "2.0000", "GBP", LocalDate.of(2026, 1, 1), null);
        PurchaseOrderLine line = createLine(poAId, skuId, 22);

        PurchaseOrderLine priced = priceLine(line);

        assertThat(priced.getCartonValid()).isFalse();
        assertThat(priced.getAdjustedQuantity()).isEqualTo(20);
    }

    @Test
    void flagsAnExpiredPriceWithoutDroppingTheLine() {
        UUID skuId = seedSku(supplierAId, null);
        // Price window entirely in the past - nothing covers today.
        seedPrice(skuId, "2.0000", "GBP", LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31));
        PurchaseOrderLine line = createLine(poAId, skuId, 10);

        PurchaseOrderLine priced = priceLine(line);

        assertThat(priced.getPriceFound()).isFalse();
        assertThat(priced.getUnitPrice()).isNull();
        assertThat(priced.getAppliedTierThreshold()).isNull();
        assertThat(priced.getPricedAsOfDate()).isEqualTo(LocalDate.now());
        // Carton validity is independent of pricing and still populated.
        assertThat(priced.getCartonValid()).isTrue();
        assertThat(priced.getAdjustedQuantity()).isEqualTo(10);
    }

    @Test
    void rejectsASkuThatBelongsToAnotherSupplier() {
        UUID otherSuppliersSkuId = seedSku(otherSupplierId, null);
        seedPrice(otherSuppliersSkuId, "2.0000", "GBP", LocalDate.of(2026, 1, 1), null);
        PurchaseOrderLine line = createLine(poAId, otherSuppliersSkuId, 10);

        assertThatThrownBy(() -> priceLine(line)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void rejectsALineThatResolvesToADifferentCurrencyThanThePosExistingLines() {
        UUID skuGbp = seedSku(supplierAId, null);
        seedPrice(skuGbp, "2.0000", "GBP", LocalDate.of(2026, 1, 1), null);
        priceLine(createLine(poAId, skuGbp, 10));

        UUID skuUsd = seedSku(supplierAId, null);
        seedPrice(skuUsd, "3.0000", "USD", LocalDate.of(2026, 1, 1), null);
        PurchaseOrderLine secondLine = createLine(poAId, skuUsd, 10);

        // ConflictException.code() is package-private by design (see its
        // Javadoc — only inspectable through the HTTP boundary/ApiExceptionHandler,
        // which no endpoint reaches yet for this story), so the exception
        // type is what this test can verify from here.
        assertThatThrownBy(() -> priceLine(secondLine)).isInstanceOf(ConflictException.class);
    }

    @Test
    void rejectsNonPositiveQuantity() {
        UUID skuId = seedSku(supplierAId, null);
        seedPrice(skuId, "2.0000", "GBP", LocalDate.of(2026, 1, 1), null);
        PurchaseOrderLine line = createLine(poAId, skuId, 0);

        assertThatThrownBy(() -> priceLine(line)).isInstanceOf(ValidationException.class);
    }

    @Test
    void aLineReferencingAnotherCompanysPurchaseOrderIsRejected() {
        UUID skuId = seedSku(supplierAId, null);
        seedPrice(skuId, "2.0000", "GBP", LocalDate.of(2026, 1, 1), null);
        PurchaseOrderLine line = createLine(poBId, skuId, 10);

        assertThatThrownBy(() -> priceLine(line)).isInstanceOf(NotFoundException.class);
    }
}
