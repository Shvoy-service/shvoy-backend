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
 * The 6.5 re-spec's distinctive behaviours, driven through the real {@link
 * ThreeWayMatchService}: per-coverage strategies at PO level, the rollup that
 * catches collective over-claim, and the terms-type consequence split
 * (per-payment gating vs record-only for rolling). The per-strategy amount/leg
 * rules have a pure unit test ({@link InvoiceMatchEvaluatorTest}); this covers
 * what needs the whole pipeline.
 */
@SpringBootTest
@ActiveProfiles("test")
class InvoiceMatchRespecTest {

    @Autowired
    ThreeWayMatchService matchService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID company = UUID.randomUUID();
    UUID userId;
    UUID supplierId;
    UUID skuId;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", company, "Co", now);
        userId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userId, "u-" + userId + "@x.com", now, company);
        supplierId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierId, "Sup", now, company);
        skuId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, description, status, created_at, company_id) "
                + "VALUES (?, ?, 'SKU-1', 'Widget', 'ACTIVE', ?, ?)",
            skuId, supplierId, now, company);
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        CurrentUserContext.clear();
        jdbcTemplate.update("DELETE FROM discrepancy_case_audit_events WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM discrepancy_cases WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM payment_audit_events WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM payment_grn_projection_lines WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM invoice_match_results WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM invoices WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM proforma_invoice_lines WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM proforma_invoices WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM payments WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", company);
        jdbcTemplate.update("UPDATE suppliers SET current_term_id = NULL WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM payment_terms WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM users WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM companies WHERE id = ?", company);
    }

    @Test
    void twoShipmentInvoicesCollectivelyOverClaimingFailTheSecondAtRollup() {
        UUID po = generatedPo();
        poLine(po, 10, "2.0000");
        insertBalance(po, "20.00");
        confirmedPi(po, 10, "2.0000");
        UUID consignment = grnProjection(po, consignmentId(), 10); // received value 20.00
        UUID first = shipmentInvoice(po, "INV-1", "20.00", consignment, 0);
        UUID second = shipmentInvoice(po, "INV-2", "20.00", consignment, 5); // same consignment, double-billed

        evaluate(po);

        assertThat(passedOf(first)).isTrue();
        assertThat(passedOf(second)).isFalse();
        assertThat(detailOf(second)).contains("Rollup");
        assertThat(balanceStatus(po)).isEqualTo("BLOCKED"); // block-by-default on the collective over-claim
    }

    @Test
    void aRollingSupplierRecordsTheVerdictAndOpensACaseButNeverTransitionsThePayment() {
        rollingTerms();
        UUID po = generatedPo();
        poLine(po, 10, "2.0000");
        UUID balance = insertBalance(po, "20.00");
        confirmedPi(po, 10, "2.0000");
        grnProjection(po, consignmentId(), 10);
        UUID inv = balanceInvoice(po, "25.00", 0); // wrong amount -> fails

        evaluate(po);

        assertThat(passedOf(inv)).isFalse();
        assertThat(policyOf(inv)).isEqualTo("STATEMENT_RECORDED");
        assertThat(caseCount(po)).isEqualTo(1); // a rolling supplier can still short-ship — the case opens
        assertThat(statusOf(balance)).isEqualTo("PENDING"); // ...but NO per-PO payment transition
    }

    @Test
    void aDepositInvoiceGatesTheDepositWithoutAnyReceipt() {
        UUID po = generatedPo();
        poLine(po, 10, "2.0000");
        UUID deposit = insertDeposit(po, "6.00");
        insertBalance(po, "14.00");
        confirmedPi(po, 10, "2.0000");
        UUID inv = depositInvoice(po, "6.00");

        evaluate(po);

        assertThat(passedOf(inv)).isTrue();
        assertThat(statusOf(deposit)).isEqualTo("READY_TO_PAY");
    }

    @Test
    void anAmountInvoicePositionMatchesButDoesNotReleaseTheBalance() {
        UUID po = generatedPo();
        poLine(po, 10, "2.0000");
        UUID balance = insertBalance(po, "20.00");
        confirmedPi(po, 10, "2.0000");
        grnProjection(po, consignmentId(), 10); // received value 20.00
        UUID inv = amountInvoice(po, "15.00");

        evaluate(po);

        assertThat(passedOf(inv)).isTrue();
        assertThat(positionMatchedOf(inv)).isTrue(); // flagged loose
        assertThat(statusOf(balance)).isEqualTo("PENDING"); // AMOUNT never completes the balance
    }

    @Test
    void aNewPartialShipmentFlipsABlockedBalanceToReadyToPay() {
        UUID po = generatedPo();
        poLine(po, 10, "2.0000");
        UUID balance = insertBalance(po, "20.00");
        confirmedPi(po, 10, "2.0000");
        grnProjection(po, consignmentId(), 8); // short — receipt incomplete
        balanceInvoice(po, "20.00", 0);

        evaluate(po);
        assertThat(statusOf(balance)).isEqualTo("BLOCKED");
        assertThat(matchDetailOf(balance)).contains("Receipt incomplete");

        grnProjection(po, consignmentId(), 2); // the rest arrives -> cumulative 10 == ordered
        evaluate(po);
        assertThat(statusOf(balance)).isEqualTo("READY_TO_PAY");
    }

    // --- driving ---

    private void evaluate(UUID po) {
        TenantContext.set(company);
        CurrentUserContext.set(userId);
        try {
            matchService.evaluate(po);
        } finally {
            TenantContext.clear();
            CurrentUserContext.clear();
        }
    }

    // --- seeding ---

    private void rollingTerms() {
        UUID termId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO payment_terms (id, company_id, supplier_id, terms_type, deposit_pct, anchor_date_type, days_from_anchor, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'ROLLING', NULL, 'STATEMENT_DATE', 0, ?, ?)",
            termId, company, supplierId, now, now);
        jdbcTemplate.update("UPDATE suppliers SET current_term_id = ? WHERE id = ?", termId, supplierId);
    }

    private UUID generatedPo() {
        UUID po = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, generated_at, company_id) "
                + "VALUES (?, ?, ?, 'GENERATED', ?, ?, ?, ?)",
            po, supplierId, "PO-" + po, userId, now, now, company);
        return po;
    }

    private void poLine(UUID po, int qty, String price) {
        jdbcTemplate.update(
            "INSERT INTO purchase_order_lines (id, company_id, purchase_order_id, sku_id, line_number, quantity, unit_price_amount, currency, price_found, created_at) "
                + "VALUES (?, ?, ?, ?, 1, ?, ?, 'USD', TRUE, ?)",
            UUID.randomUUID(), company, po, skuId, qty, new BigDecimal(price), Timestamp.from(Instant.now()));
    }

    private UUID insertBalance(UUID po, String amount) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO payments (id, company_id, purchase_order_id, type, amount_amount, currency, status, created_at, anchor_event, days_offset) "
                + "VALUES (?, ?, ?, 'BALANCE', ?, 'USD', 'PENDING', ?, 'BL', 30)",
            id, company, po, new BigDecimal(amount), Timestamp.from(Instant.now()));
        return id;
    }

    private UUID insertDeposit(UUID po, String amount) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO payments (id, company_id, purchase_order_id, type, amount_amount, currency, status, created_at) "
                + "VALUES (?, ?, ?, 'DEPOSIT', ?, 'USD', 'PENDING', ?)",
            id, company, po, new BigDecimal(amount), Timestamp.from(Instant.now()));
        return id;
    }

    private void confirmedPi(UUID po, int qty, String price) {
        UUID piId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO proforma_invoices (id, purchase_order_id, pi_reference, currency, status, active, logged_by, created_at, company_id) "
                + "VALUES (?, ?, ?, 'USD', 'AUTO_CONFIRMED', TRUE, ?, ?, ?)",
            piId, po, "PI-" + piId, userId, now, company);
        jdbcTemplate.update(
            "INSERT INTO proforma_invoice_lines (id, company_id, proforma_invoice_id, sku_id, line_number, confirmed_unit_price_amount, confirmed_quantity, created_at) "
                + "VALUES (?, ?, ?, ?, 1, ?, ?, ?)",
            UUID.randomUUID(), company, piId, skuId, new BigDecimal(price), qty, now);
    }

    private UUID consignmentId() {
        return UUID.randomUUID();
    }

    private UUID grnProjection(UUID po, UUID consignment, int qty) {
        jdbcTemplate.update(
            "INSERT INTO payment_grn_projection_lines (id, company_id, purchase_order_id, consignment_id, sku_id, received_quantity, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), company, po, consignment, skuId, qty, Timestamp.from(Instant.now()));
        return consignment;
    }

    private UUID invoice(UUID po, String ref, String amount, String coversType, UUID consignment, int secondsOffset) {
        UUID id = UUID.randomUUID();
        Timestamp created = Timestamp.from(Instant.now().plusSeconds(secondsOffset));
        jdbcTemplate.update(
            "INSERT INTO invoices (id, company_id, purchase_order_id, invoice_reference, amount_amount, currency, invoice_date, covers_type, covers_consignment_id, status, active, logged_by, created_at) "
                + "VALUES (?, ?, ?, ?, ?, 'USD', ?, ?, ?, 'LOGGED', TRUE, ?, ?)",
            id, company, po, ref, new BigDecimal(amount), Date.valueOf(LocalDate.now()), coversType, consignment,
            userId, created);
        return id;
    }

    private UUID shipmentInvoice(UUID po, String ref, String amount, UUID consignment, int secondsOffset) {
        return invoice(po, ref, amount, "SHIPMENT", consignment, secondsOffset);
    }

    private UUID balanceInvoice(UUID po, String amount, int secondsOffset) {
        return invoice(po, "INV-B", amount, "BALANCE", null, secondsOffset);
    }

    private UUID depositInvoice(UUID po, String amount) {
        return invoice(po, "INV-D", amount, "DEPOSIT", null, 0);
    }

    private UUID amountInvoice(UUID po, String amount) {
        return invoice(po, "INV-A", amount, "AMOUNT", null, 0);
    }

    // --- assertions ---

    private boolean passedOf(UUID invoiceId) {
        return jdbcTemplate.queryForObject(
            "SELECT passed FROM invoice_match_results WHERE invoice_id = ?", Boolean.class, invoiceId);
    }

    private boolean positionMatchedOf(UUID invoiceId) {
        return jdbcTemplate.queryForObject(
            "SELECT position_matched FROM invoice_match_results WHERE invoice_id = ?", Boolean.class, invoiceId);
    }

    private String detailOf(UUID invoiceId) {
        return jdbcTemplate.queryForObject(
            "SELECT detail FROM invoice_match_results WHERE invoice_id = ?", String.class, invoiceId);
    }

    private String policyOf(UUID invoiceId) {
        return jdbcTemplate.queryForObject(
            "SELECT policy_applied FROM invoice_match_results WHERE invoice_id = ?", String.class, invoiceId);
    }

    private String statusOf(UUID paymentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    private String balanceStatus(UUID po) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM payments WHERE purchase_order_id = ? AND type = 'BALANCE'", String.class, po);
    }

    private String matchDetailOf(UUID paymentId) {
        return jdbcTemplate.queryForObject("SELECT match_detail FROM payments WHERE id = ?", String.class, paymentId);
    }

    private int caseCount(UUID po) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM discrepancy_cases WHERE purchase_order_id = ?", Integer.class, po);
    }
}
