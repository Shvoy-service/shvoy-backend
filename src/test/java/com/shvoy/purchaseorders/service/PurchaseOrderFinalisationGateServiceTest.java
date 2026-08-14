package com.shvoy.purchaseorders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.shvoy.ConflictException;
import com.shvoy.CurrentUserContext;
import com.shvoy.NotFoundException;
import com.shvoy.TenantContext;
import com.shvoy.ValidationException;
import com.shvoy.purchaseorders.domain.PurchaseOrderPriceOverride;
import com.shvoy.purchaseorders.dto.ExpiredPriceOverrideRequest;
import com.shvoy.purchaseorders.dto.LineOverridePrice;

/**
 * No class-level @Transactional, JDBC seeding, TenantContext/CurrentUserContext
 * set explicitly around every call — same conventions as
 * PurchaseOrderLinePricingServiceTest (checkFinalisationGate is
 * @Transactional against this app's JPA-backed transaction manager, which
 * needs a resolvable tenant even for the pure-JDBC parts of this service).
 * No controller exists for this story (see the service's own Javadoc), so
 * these are the only tests for this gate — 4.6 will add HTTP-boundary tests
 * once it wires this into a real finalise endpoint.
 */
@SpringBootTest
@ActiveProfiles("test")
class PurchaseOrderFinalisationGateServiceTest {

    @Autowired
    PurchaseOrderFinalisationGateService gateService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID supplierAId;
    UUID userAId;
    UUID poAId;

    @BeforeEach
    void seedCompanySupplierUserAndPo() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);

        supplierAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierAId, "Supplier A", now, companyA);

        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "admin-a@example.com", now, companyA);

        poAId = seedPo(supplierAId, companyA);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM purchase_order_price_override_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_order_price_overrides WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM sku_prices WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private UUID seedPo(UUID supplierId, UUID companyId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, 'PO-0001', 'DRAFT', ?, ?, ?)",
            id, supplierId, userAId, Timestamp.from(Instant.now()), companyId);
        return id;
    }

    private UUID seedSku(UUID supplierId, UUID companyId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, status, created_at, company_id) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
            id, supplierId, "SKU-" + id, Timestamp.from(Instant.now()), companyId);
        return id;
    }

    private void seedPrice(UUID skuId, String amount, String currency, LocalDate validFrom, LocalDate validTo, UUID companyId) {
        jdbcTemplate.update(
            "INSERT INTO sku_prices (id, sku_id, unit_price_amount, currency, valid_from, valid_to, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), skuId, new BigDecimal(amount), currency, Date.valueOf(validFrom),
            validTo == null ? null : Date.valueOf(validTo), Timestamp.from(Instant.now()), companyId);
    }

    private UUID seedLine(UUID poId, UUID skuId, int lineNumber, int quantity, UUID companyId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO purchase_order_lines (id, purchase_order_id, sku_id, line_number, quantity, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, poId, skuId, lineNumber, quantity, Timestamp.from(Instant.now()), companyId);
        return id;
    }

    private Optional<PurchaseOrderPriceOverride> checkGate(UUID poId, ExpiredPriceOverrideRequest override) {
        TenantContext.set(companyA);
        CurrentUserContext.set(userAId);
        try {
            return gateService.checkFinalisationGate(poId, override);
        } finally {
            TenantContext.clear();
            CurrentUserContext.clear();
        }
    }

    @Test
    void cleanPoWithAllLinesValidlyPricedPasses() {
        UUID skuId = seedSku(supplierAId, companyA);
        seedPrice(skuId, "2.0000", "USD", LocalDate.of(2026, 1, 1), null, companyA);
        seedLine(poAId, skuId, 1, 10, companyA);

        Optional<PurchaseOrderPriceOverride> result = checkGate(poAId, null);

        assertThat(result).isEmpty();
        Long overrideCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchase_order_price_overrides", Long.class);
        assertThat(overrideCount).isZero();
    }

    @Test
    void expiredLineBlocksWithoutOverride() {
        UUID skuId = seedSku(supplierAId, companyA);
        seedPrice(skuId, "2.0000", "USD", LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31), companyA);
        UUID lineId = seedLine(poAId, skuId, 1, 10, companyA);

        assertThatThrownBy(() -> checkGate(poAId, null))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining(lineId.toString())
            .hasMessageContaining("EXPIRED");
    }

    @Test
    void neverPricedLineBlocksAndIsDistinguishableFromExpired() {
        UUID neverPricedSkuId = seedSku(supplierAId, companyA);
        UUID lineNeverPriced = seedLine(poAId, neverPricedSkuId, 1, 10, companyA);

        UUID expiredSkuId = seedSku(supplierAId, companyA);
        seedPrice(expiredSkuId, "2.0000", "USD", LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31), companyA);
        UUID lineExpired = seedLine(poAId, expiredSkuId, 2, 5, companyA);

        assertThatThrownBy(() -> checkGate(poAId, null))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining(lineNeverPriced + " (NEVER_PRICED)")
            .hasMessageContaining(lineExpired + " (EXPIRED)");
    }

    @Test
    void overrideWithReasonAndManualPriceSucceedsAndPersistsImmutableAuditTrail() {
        UUID skuId = seedSku(supplierAId, companyA);
        UUID lineId = seedLine(poAId, skuId, 1, 10, companyA);

        ExpiredPriceOverrideRequest override = new ExpiredPriceOverrideRequest(
            "Supplier confirmed price by phone, price file not yet updated",
            List.of(new LineOverridePrice(lineId, new BigDecimal("3.5000"), "USD")));

        Optional<PurchaseOrderPriceOverride> result = checkGate(poAId, override);

        assertThat(result).isPresent();
        PurchaseOrderPriceOverride saved = result.get();
        assertThat(saved.getPurchaseOrderId()).isEqualTo(poAId);
        assertThat(saved.getOverriddenBy()).isEqualTo(userAId);
        assertThat(saved.getReason()).isEqualTo("Supplier confirmed price by phone, price file not yet updated");
        assertThat(saved.getCreatedAt()).isNotNull();

        var overrideLineRow = jdbcTemplate.queryForMap(
            "SELECT purchase_order_line_id, manual_price_amount, manual_price_currency "
                + "FROM purchase_order_price_override_lines WHERE override_id = ?",
            saved.getId());
        assertThat(overrideLineRow.get("purchase_order_line_id")).isEqualTo(lineId);
        assertThat(new BigDecimal(overrideLineRow.get("manual_price_amount").toString())).isEqualByComparingTo("3.5000");
        assertThat(overrideLineRow.get("manual_price_currency")).isEqualTo("USD");
    }

    @Test
    void overrideWithoutReasonStillBlocked() {
        UUID skuId = seedSku(supplierAId, companyA);
        UUID lineId = seedLine(poAId, skuId, 1, 10, companyA);

        ExpiredPriceOverrideRequest override = new ExpiredPriceOverrideRequest(
            "", List.of(new LineOverridePrice(lineId, new BigDecimal("3.5000"), "USD")));

        assertThatThrownBy(() -> checkGate(poAId, override)).isInstanceOf(ConflictException.class);

        Long overrideCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchase_order_price_overrides", Long.class);
        assertThat(overrideCount).isZero();
    }

    @Test
    void overrideMissingAManualPriceForABlockedLineStillBlocked() {
        UUID skuId = seedSku(supplierAId, companyA);
        seedLine(poAId, skuId, 1, 10, companyA);

        ExpiredPriceOverrideRequest override = new ExpiredPriceOverrideRequest("Reason given, but no price supplied", List.of());

        assertThatThrownBy(() -> checkGate(poAId, override)).isInstanceOf(ConflictException.class);

        Long overrideCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchase_order_price_overrides", Long.class);
        assertThat(overrideCount).isZero();
    }

    @Test
    void overrideWithNonPositiveManualPriceThrowsValidationException() {
        UUID skuId = seedSku(supplierAId, companyA);
        UUID lineId = seedLine(poAId, skuId, 1, 10, companyA);

        ExpiredPriceOverrideRequest override = new ExpiredPriceOverrideRequest(
            "Bad price", List.of(new LineOverridePrice(lineId, BigDecimal.ZERO, "USD")));

        assertThatThrownBy(() -> checkGate(poAId, override)).isInstanceOf(ValidationException.class);
    }

    @Test
    void overrideWithMalformedCurrencyThrowsValidationException() {
        UUID skuId = seedSku(supplierAId, companyA);
        UUID lineId = seedLine(poAId, skuId, 1, 10, companyA);

        ExpiredPriceOverrideRequest override = new ExpiredPriceOverrideRequest(
            "Bad currency", List.of(new LineOverridePrice(lineId, new BigDecimal("3.50"), "not-a-code")));

        assertThatThrownBy(() -> checkGate(poAId, override)).isInstanceOf(ValidationException.class);
    }

    @Test
    void aPoBelongingToAnotherCompanyIsRejected() {
        UUID poBId = seedPo(supplierAId, companyB);

        assertThatThrownBy(() -> checkGate(poBId, null)).isInstanceOf(NotFoundException.class);
    }
}
