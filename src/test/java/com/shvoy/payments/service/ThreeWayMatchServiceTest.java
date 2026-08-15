package com.shvoy.payments.service;

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

import com.shvoy.CurrentUserContext;
import com.shvoy.TenantContext;

/**
 * Story 6.5 — the three-way match, driven through the real {@link
 * ThreeWayMatchService} over JDBC-seeded legs. Covers the verdicts (clean pass,
 * short-shipment, penny-over, credit agreed/unagreed), the honest awaiting vs
 * blocked distinction, the confirmed-PI-only rule, the deposit gate policy,
 * human-decision protection, and tenant isolation. The event-driven triggers
 * have their own test (ThreeWayMatchTriggerTest); the rule has a pure unit test.
 */
@SpringBootTest
@ActiveProfiles("test")
class ThreeWayMatchServiceTest {

    @Autowired
    ThreeWayMatchService matchService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID userAId;
    UUID supplierAId;
    UUID skuAId;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        userAId = insertUser(companyA);
        supplierAId = insertSupplier(companyA);
        skuAId = insertSku(supplierAId, companyA);
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        CurrentUserContext.clear();
        // Delete every user-referencing row for BOTH companies before any users — a cross-tenant
        // test seeds company B's PO/PI/invoice with company A's user, so per-company ordering isn't enough.
        for (UUID company : new UUID[] {companyA, companyB}) {
            jdbcTemplate.update("DELETE FROM discrepancy_case_audit_events WHERE company_id = ?", company);
            jdbcTemplate.update("DELETE FROM discrepancy_cases WHERE company_id = ?", company);
            jdbcTemplate.update("DELETE FROM payment_audit_events WHERE company_id = ?", company);
            jdbcTemplate.update("DELETE FROM payment_grn_projection_lines WHERE company_id = ?", company);
            jdbcTemplate.update("DELETE FROM credit_ledger_audit_events WHERE company_id = ?", company);
            jdbcTemplate.update("DELETE FROM credit_ledger_entries WHERE company_id = ?", company);
            jdbcTemplate.update("DELETE FROM invoice_match_results WHERE company_id = ?", company);
            jdbcTemplate.update("DELETE FROM invoices WHERE company_id = ?", company);
            jdbcTemplate.update("DELETE FROM proforma_invoice_lines WHERE company_id = ?", company);
            jdbcTemplate.update("DELETE FROM proforma_invoices WHERE company_id = ?", company);
            jdbcTemplate.update("DELETE FROM payments WHERE company_id = ?", company);
            jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id = ?", company);
            jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", company);
            jdbcTemplate.update("DELETE FROM skus WHERE company_id = ?", company);
            jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", company);
        }
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void cleanMatchMakesTheBalanceReadyToPay() {
        UUID po = fullyMatchablePo(companyA, 10, 10, 10, "2.0000", "20.00", null, null);
        UUID balance = balanceId(po);
        UUID deposit = insertDeposit(po, "5.00", companyA);

        evaluate(po);

        assertThat(statusOf(balance)).isEqualTo("READY_TO_PAY");
        assertThat(matchDetailOf(balance)).isNull();
        assertThat(statusOf(deposit)).isEqualTo("READY_TO_PAY"); // payable without the match
        assertThat(auditCount(po, "MATCH_PASSED")).isEqualTo(1);
    }

    @Test
    void shortShipmentWithFullInvoiceBlocks() {
        UUID po = fullyMatchablePo(companyA, 10, 10, 8, "2.0000", "20.00", null, null);
        UUID balance = balanceId(po);

        evaluate(po);

        assertThat(statusOf(balance)).isEqualTo("BLOCKED");
        assertThat(matchDetailOf(balance)).contains("Receipt incomplete");
        assertThat(auditCount(po, "MATCH_BLOCKED")).isEqualTo(1);
    }

    @Test
    void aPennyOverBlocks() {
        UUID po = fullyMatchablePo(companyA, 10, 10, 10, "2.0000", "20.01", null, null);
        evaluate(po);
        assertThat(statusOf(balanceId(po))).isEqualTo("BLOCKED");
        assertThat(matchDetailOf(balanceId(po))).contains("expected USD 20.00");
    }

    @Test
    void agreedCreditPassesAndAppliesTheLedgerEntry() {
        UUID po = fullyMatchablePo(companyA, 10, 10, 10, "2.0000", "15.00", "5.00", "CN-1");
        UUID entry = insertOpenCredit(po, "5.00", companyA);

        evaluate(po);

        assertThat(statusOf(balanceId(po))).isEqualTo("READY_TO_PAY");
        assertThat(creditStatusOf(entry)).isEqualTo("APPLIED");
        assertThat(creditTargetInvoiceOf(entry)).isNotNull();
    }

    @Test
    void unagreedClaimedCreditBlocks() {
        // Invoice claims a 5.00 credit, but no open ledger entry backs it.
        UUID po = fullyMatchablePo(companyA, 10, 10, 10, "2.0000", "15.00", "5.00", "CN-BOGUS");
        evaluate(po);
        assertThat(statusOf(balanceId(po))).isEqualTo("BLOCKED");
        assertThat(matchDetailOf(balanceId(po))).contains("Claimed credit");
    }

    @Test
    void missingInvoiceStaysPendingAwaiting_notBlocked() {
        UUID po = insertGeneratedPo(companyA);
        insertPoLine(po, skuAId, 10, "2.0000", companyA);
        UUID balance = insertBalance(po, "20.00", companyA);
        UUID deposit = insertDeposit(po, "5.00", companyA);
        insertConfirmedPi(po, "AUTO_CONFIRMED", 10, "2.0000", companyA);
        insertGrnProjection(po, 10, companyA);
        // no invoice

        evaluate(po);

        assertThat(statusOf(balance)).isEqualTo("PENDING");
        assertThat(matchDetailOf(balance)).contains("Awaiting").contains("invoice");
        assertThat(statusOf(deposit)).isEqualTo("READY_TO_PAY"); // deposit payable pre-match
        assertThat(auditCount(po, "MATCH_BLOCKED")).isZero();
    }

    @Test
    void anUnconfirmedPiIsNotMatchable() {
        UUID po = insertGeneratedPo(companyA);
        insertPoLine(po, skuAId, 10, "2.0000", companyA);
        UUID balance = insertBalance(po, "20.00", companyA);
        insertConfirmedPi(po, "ROUTED_FOR_APPROVAL", 10, "2.0000", companyA); // pending approval — not a confirmed leg
        insertGrnProjection(po, 10, companyA);
        insertInvoice(po, "20.00", null, null, companyA);

        evaluate(po);

        assertThat(statusOf(balance)).isEqualTo("PENDING");
        assertThat(matchDetailOf(balance)).contains("confirmed PI");
    }

    @Test
    void aHumanHoldIsNeverOverriddenByTheMatch() {
        UUID po = fullyMatchablePo(companyA, 10, 10, 10, "2.0000", "20.00", null, null);
        UUID balance = balanceId(po);
        jdbcTemplate.update("UPDATE payments SET status = 'ON_HOLD' WHERE id = ?", balance);

        evaluate(po);

        assertThat(statusOf(balance)).isEqualTo("ON_HOLD");
    }

    @Test
    void evaluatingOnePoDoesNotTouchAnotherTenant() {
        UUID poA = fullyMatchablePo(companyA, 10, 10, 10, "2.0000", "20.00", null, null);
        UUID poB = fullyMatchablePo(companyB, 10, 10, 10, "2.0000", "20.00", null, null);

        evaluate(poA);

        assertThat(statusOf(balanceId(poA))).isEqualTo("READY_TO_PAY");
        assertThat(statusOf(balanceId(poB))).isEqualTo("PENDING"); // untouched
    }

    // --- driving the service ---

    private void evaluate(UUID purchaseOrderId) {
        UUID company = jdbcTemplate.queryForObject(
            "SELECT company_id FROM purchase_orders WHERE id = ?", UUID.class, purchaseOrderId);
        TenantContext.set(company);
        CurrentUserContext.set(userAId);
        try {
            matchService.evaluate(purchaseOrderId);
        } finally {
            TenantContext.clear();
            CurrentUserContext.clear();
        }
    }

    /** A PO with every leg present and consistent by default; tweak the quantities/amount/credit to break a leg. */
    private UUID fullyMatchablePo(UUID company, int poQty, int piQty, int grnQty, String piPrice, String invoiceAmount,
            String claimedCredit, String claimedCreditRef) {
        UUID po = insertGeneratedPo(company);
        insertPoLine(po, skuForCompany(company), poQty, piPrice, company);
        insertBalance(po, "20.00", company);
        insertConfirmedPi(po, "AUTO_CONFIRMED", piQty, piPrice, company);
        insertGrnProjection(po, grnQty, company);
        insertInvoice(po, invoiceAmount, claimedCredit, claimedCreditRef, company);
        return po;
    }

    private UUID skuForCompany(UUID company) {
        if (company.equals(companyA)) {
            return skuAId;
        }
        UUID supplier = insertSupplier(company);
        return insertSku(supplier, company);
    }

    // --- seed helpers ---

    private UUID insertUser(UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            id, "u-" + id + "@x.com", Timestamp.from(Instant.now()), company);
        return id;
    }

    private UUID insertSupplier(UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            id, "Sup-" + id, Timestamp.from(Instant.now()), company);
        return id;
    }

    private UUID insertSku(UUID supplier, UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, description, status, created_at, company_id) "
                + "VALUES (?, ?, ?, 'Widget', 'ACTIVE', ?, ?)",
            id, supplier, "SKU-" + id, Timestamp.from(Instant.now()), company);
        return id;
    }

    private UUID insertGeneratedPo(UUID company) {
        UUID id = UUID.randomUUID();
        UUID supplier = company.equals(companyA) ? supplierAId : insertSupplier(company);
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, generated_at, company_id) "
                + "VALUES (?, ?, ?, 'GENERATED', ?, ?, ?, ?)",
            id, supplier, "PO-" + id, userAId, now, now, company);
        return id;
    }

    private void insertPoLine(UUID po, UUID sku, int qty, String price, UUID company) {
        jdbcTemplate.update(
            "INSERT INTO purchase_order_lines "
                + "(id, company_id, purchase_order_id, sku_id, line_number, quantity, unit_price_amount, currency, price_found, created_at) "
                + "VALUES (?, ?, ?, ?, 1, ?, ?, 'USD', TRUE, ?)",
            UUID.randomUUID(), company, po, sku, qty, new BigDecimal(price), Timestamp.from(Instant.now()));
    }

    private UUID insertBalance(UUID po, String amount, UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO payments (id, company_id, purchase_order_id, type, amount_amount, currency, status, created_at, anchor_event, days_offset) "
                + "VALUES (?, ?, ?, 'BALANCE', ?, 'USD', 'PENDING', ?, 'BL', 30)",
            id, company, po, new BigDecimal(amount), Timestamp.from(Instant.now()));
        return id;
    }

    private UUID insertDeposit(UUID po, String amount, UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO payments (id, company_id, purchase_order_id, type, amount_amount, currency, status, created_at) "
                + "VALUES (?, ?, ?, 'DEPOSIT', ?, 'USD', 'PENDING', ?)",
            id, company, po, new BigDecimal(amount), Timestamp.from(Instant.now()));
        return id;
    }

    private void insertConfirmedPi(UUID po, String status, int qty, String price, UUID company) {
        UUID piId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO proforma_invoices (id, purchase_order_id, pi_reference, currency, status, active, logged_by, created_at, company_id) "
                + "VALUES (?, ?, ?, 'USD', ?, TRUE, ?, ?, ?)",
            piId, po, "PI-" + piId, status, userAId, now, company);
        jdbcTemplate.update(
            "INSERT INTO proforma_invoice_lines (id, company_id, proforma_invoice_id, sku_id, line_number, confirmed_unit_price_amount, confirmed_quantity, created_at) "
                + "VALUES (?, ?, ?, ?, 1, ?, ?, ?)",
            UUID.randomUUID(), company, piId, skuForCompany(company), new BigDecimal(price), qty, now);
    }

    private void insertInvoice(UUID po, String amount, String claimedCredit, String claimedRef, UUID company) {
        jdbcTemplate.update(
            "INSERT INTO invoices (id, company_id, purchase_order_id, invoice_reference, amount_amount, currency, invoice_date, claimed_credit_amount, claimed_credit_reference, covers_type, status, active, logged_by, created_at) "
                + "VALUES (?, ?, ?, ?, ?, 'USD', ?, ?, ?, 'BALANCE', 'LOGGED', TRUE, ?, ?)",
            UUID.randomUUID(), company, po, "INV-1", new BigDecimal(amount), Date.valueOf(LocalDate.now()),
            claimedCredit == null ? null : new BigDecimal(claimedCredit), claimedRef, userAId,
            Timestamp.from(Instant.now()));
    }

    private void insertGrnProjection(UUID po, int qty, UUID company) {
        jdbcTemplate.update(
            "INSERT INTO payment_grn_projection_lines (id, company_id, purchase_order_id, consignment_id, sku_id, received_quantity, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), company, po, UUID.randomUUID(), skuForCompany(company), qty, Timestamp.from(Instant.now()));
    }

    private UUID insertOpenCredit(UUID po, String amount, UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO credit_ledger_entries (id, company_id, purchase_order_id, amount_amount, currency, cause, status, logged_by, created_at) "
                + "VALUES (?, ?, ?, ?, 'USD', 'SHORT_SHIPMENT', 'OPEN', ?, ?)",
            id, company, po, new BigDecimal(amount), userAId, Timestamp.from(Instant.now()));
        return id;
    }

    // --- assertions ---

    private UUID balanceId(UUID po) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM payments WHERE purchase_order_id = ? AND type = 'BALANCE'", UUID.class, po);
    }

    private String statusOf(UUID paymentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    private String matchDetailOf(UUID paymentId) {
        return jdbcTemplate.queryForObject("SELECT match_detail FROM payments WHERE id = ?", String.class, paymentId);
    }

    private String creditStatusOf(UUID entryId) {
        return jdbcTemplate.queryForObject("SELECT status FROM credit_ledger_entries WHERE id = ?", String.class, entryId);
    }

    private UUID creditTargetInvoiceOf(UUID entryId) {
        return jdbcTemplate.queryForObject(
            "SELECT target_invoice_id FROM credit_ledger_entries WHERE id = ?", UUID.class, entryId);
    }

    private int auditCount(UUID po, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_audit_events WHERE purchase_order_id = ? AND event_type = ?",
            Integer.class, po, eventType);
    }
}
