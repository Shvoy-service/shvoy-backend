package com.shvoy.purchaseorders.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.shvoy.TenantContext;
import com.shvoy.UnitPrice;
import com.shvoy.purchaseorders.domain.PurchaseOrder;

/**
 * The heart of Story 4.3 — see the class Javadoc on PurchaseOrderTotalsService.
 * Seeds already-priced lines directly via JDBC (bypassing the 4.2 pricing
 * pipeline) so each fixture's numbers are exact and deliberate, not
 * incidental — see the story's own note that a green suite that never
 * hits the divergent cases proves nothing.
 *
 * No class-level @Transactional — see SupplierTenantIsolationTest's
 * Javadoc; TenantContext is set explicitly around every recompute() call,
 * same reasoning as PoNumberGeneratorTest (recompute is @Transactional
 * against this app's JPA-backed transaction manager, which needs a
 * resolvable tenant even for the JDBC-only parts).
 */
@SpringBootTest
@ActiveProfiles("test")
class PurchaseOrderTotalsServiceTest {

    @Autowired
    PurchaseOrderTotalsService purchaseOrderTotalsService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    UUID supplierId;
    UUID userId;
    UUID poId;

    @BeforeEach
    void seedCompanySupplierUserAndPo() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);

        supplierId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierId, "Supplier A", now, companyA);

        userId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userId, "admin@example.com", now, companyA);

        poId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, 'PO-0001', 'DRAFT', ?, ?, ?)",
            poId, supplierId, userId, now, companyA);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM payment_terms WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM users WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM companies WHERE id = ?", companyA);
    }

    private UUID seedSku() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, status, created_at, company_id) "
                + "VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
            id, supplierId, "SKU-" + id, Timestamp.from(Instant.now()), companyA);
        return id;
    }

    /**
     * Computes the correct 2dp line total via the real UnitPrice#multiply
     * (production code — this is the value the pricing pipeline would
     * actually store) and inserts a line carrying it directly, skipping
     * price-resolution itself so each fixture's numbers are exact and
     * under the test's full control.
     */
    private void seedPricedLine(String unitPriceAmount, int quantity, String currency) {
        UnitPrice unitPrice = new UnitPrice(new BigDecimal(unitPriceAmount), currency);
        BigDecimal lineTotal = unitPrice.multiply(quantity).amount();
        jdbcTemplate.update(
            "INSERT INTO purchase_order_lines (id, purchase_order_id, sku_id, line_number, quantity, "
                + "unit_price_amount, currency, price_found, line_total_amount, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?, ?)",
            UUID.randomUUID(), poId, seedSku(), 1, quantity, unitPrice.amount(), currency, lineTotal,
            Timestamp.from(Instant.now()), companyA);
    }

    private void seedUnpricedLine() {
        jdbcTemplate.update(
            "INSERT INTO purchase_order_lines (id, purchase_order_id, sku_id, line_number, quantity, "
                + "price_found, created_at, company_id) VALUES (?, ?, ?, 1, 5, FALSE, ?, ?)",
            UUID.randomUUID(), poId, seedSku(), Timestamp.from(Instant.now()), companyA);
    }

    private void seedPaymentTerms(String depositPercentage) {
        jdbcTemplate.update(
            "INSERT INTO payment_terms (supplier_id, deposit_percentage, anchor_event, days_offset, created_at, company_id) "
                + "VALUES (?, ?, 'BL', 30, ?, ?)",
            supplierId, new BigDecimal(depositPercentage), Timestamp.from(Instant.now()), companyA);
    }

    private PurchaseOrder recompute() {
        TenantContext.set(companyA);
        try {
            return purchaseOrderTotalsService.recompute(poId);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aLineTotalThatRequiresRoundingIsIncludedAtItsRoundedValue() {
        // 1.4275 x 3 = 4.2825 - doesn't land on a clean 2dp value.
        seedPricedLine("1.4275", 3, "GBP");

        PurchaseOrder result = recompute();

        assertThat(result.getOrderTotal().amount()).isEqualByComparingTo("4.28");
    }

    @Test
    void orderTotalIsTheSumOfAlreadyRoundedLineTotalsNotARoundedSumOfUnroundedValues() {
        // Each line: unit price 0.0125 x qty 10 = raw 0.1250, an exact
        // HALF_EVEN tie between 0.12/0.13 - rounds to 0.12 (2 is even).
        seedPricedLine("0.0125", 10, "GBP");
        seedPricedLine("0.0125", 10, "GBP");
        seedPricedLine("0.0125", 10, "GBP");

        PurchaseOrder result = recompute();

        // Correct, per the contract: sum the already-rounded lines.
        // 0.12 + 0.12 + 0.12 = 0.36.
        assertThat(result.getOrderTotal().amount()).isEqualByComparingTo("0.36");

        // Prove this genuinely differs from the naive alternative (sum the
        // raw unrounded products, round once at the end): 0.1250 x 3 =
        // 0.3750, which ties between 0.37/0.38 - HALF_EVEN picks 0.38 (8 is
        // even). A different answer, so this fixture actually exercises
        // the composition rule rather than happening to agree with it.
        BigDecimal naiveSumThenRound = new BigDecimal("0.1250").multiply(BigDecimal.valueOf(3))
            .setScale(2, RoundingMode.HALF_EVEN);
        assertThat(naiveSumThenRound).isEqualByComparingTo("0.38");
        assertThat(result.getOrderTotal().amount()).isNotEqualByComparingTo(naiveSumThenRound);
    }

    @Test
    void aTiedValueRoundsHalfEvenNotHalfUp() {
        // 0.1250 x 1 = 0.1250 exactly - ties between 0.12 and 0.13.
        // HALF_UP would give 0.13; HALF_EVEN gives 0.12 (even).
        seedPricedLine("0.1250", 1, "GBP");

        PurchaseOrder result = recompute();

        assertThat(result.getOrderTotal().amount()).isEqualByComparingTo("0.12");
    }

    @Test
    void unpricedLinesDoNotContributeToTheOrderTotal() {
        seedPricedLine("2.0000", 5, "GBP");
        seedUnpricedLine();

        PurchaseOrder result = recompute();

        assertThat(result.getOrderTotal().amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void aPoWithNoPricedLinesHasNoOrderTotal() {
        seedUnpricedLine();

        PurchaseOrder result = recompute();

        assertThat(result.getOrderTotal()).isNull();
        assertThat(result.getDeposit()).isNull();
        assertThat(result.getBalance()).isNull();
    }

    @Test
    void depositAndBalanceSplitOnAnOddTotalReconcileExactly() {
        seedPaymentTerms("30");
        // 100.01 doesn't divide cleanly by 30%.
        seedPricedLine("100.0100", 1, "GBP");

        PurchaseOrder result = recompute();

        assertThat(result.getOrderTotal().amount()).isEqualByComparingTo("100.01");
        assertThat(result.getDeposit().amount()).isEqualByComparingTo("30.00");
        assertThat(result.getBalance().amount()).isEqualByComparingTo("70.01");
        assertThat(result.getDeposit().plus(result.getBalance())).isEqualTo(result.getOrderTotal());
    }

    @Test
    void noPaymentTermsLeavesDepositAndBalanceNullRatherThanStale() {
        seedPricedLine("2.0000", 5, "GBP");

        PurchaseOrder result = recompute();

        assertThat(result.getOrderTotal()).isNotNull();
        assertThat(result.getDeposit()).isNull();
        assertThat(result.getBalance()).isNull();
    }
}
