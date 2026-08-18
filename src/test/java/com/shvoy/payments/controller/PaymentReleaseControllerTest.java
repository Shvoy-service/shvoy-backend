package com.shvoy.payments.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Story 6.8 — Pay / Hold / Release actions and the enforced lifecycle. Drives
 * the real controller over JDBC-seeded payments in each source status; covers
 * the Finance-only authority, the distinct rejections, the mandatory hold
 * reason, hold-then-release re-checking the current verdict, a re-match blocking
 * a held payment, and PAID being terminal.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "FINANCE")
class PaymentReleaseControllerTest {

    private static final String TENANT = "X-Debug-Company-Id";
    private static final String USER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID userAId;
    UUID supplierAId;
    UUID supplierBId;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "fin-" + userAId + "@x.com", now, companyA);
        supplierAId = insertSupplier(companyA);
        supplierBId = insertSupplier(companyB);
    }

    private UUID insertSupplier(UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            id, "Sup-" + id, Timestamp.from(Instant.now()), company);
        return id;
    }

    @AfterEach
    void cleanUp() {
        com.shvoy.TenantContext.clear();
        com.shvoy.CurrentUserContext.clear();
        for (UUID c : new UUID[] {companyA, companyB}) {
            jdbcTemplate.update("DELETE FROM discrepancy_case_audit_events WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM discrepancy_cases WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM payment_audit_events WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM invoice_match_results WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM invoices WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM proforma_invoice_lines WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM proforma_invoices WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM payment_grn_projection_lines WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM payments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM skus WHERE company_id = ?", c);
            jdbcTemplate.update("UPDATE suppliers SET current_term_id = NULL WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM payment_terms WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", c);
        }
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    // --- Pay ---

    @Test
    void payTransitionsReadyToPayToPaidWithReferenceAndBackdatedDate() throws Exception {
        UUID payment = seedPayment(companyA, "READY_TO_PAY");

        pay(payment, "{\"paidDate\":\"2026-03-01\",\"paymentReference\":\"BATCH-42\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAID"))
            .andExpect(jsonPath("$.paidDate").value("2026-03-01"))
            .andExpect(jsonPath("$.paymentReference").value("BATCH-42"));

        assertThat(statusOf(payment)).isEqualTo("PAID");
        assertThat(auditCount(payment, "PAID")).isEqualTo(1);
    }

    @Test
    void payWithNoBodyDefaultsThePaidDateToToday() throws Exception {
        UUID payment = seedPayment(companyA, "READY_TO_PAY");

        mockMvc.perform(post("/api/payments/{id}/pay", payment)
                .header(TENANT, companyA.toString()).header(USER, userAId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAID"))
            .andExpect(jsonPath("$.paidDate").value(java.time.LocalDate.now().toString()));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void payByPurchasingIsForbidden() throws Exception {
        UUID payment = seedPayment(companyA, "READY_TO_PAY");
        pay(payment, "{}").andExpect(status().isForbidden());
        assertThat(statusOf(payment)).isEqualTo("READY_TO_PAY");
    }

    @Test
    void payingAPendingPaymentIsRejected() throws Exception {
        UUID payment = seedPayment(companyA, "PENDING");
        pay(payment, "{}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_NOT_PAYABLE"));
    }

    @Test
    void payingABlockedPaymentIsRejected() throws Exception {
        UUID payment = seedPayment(companyA, "BLOCKED");
        pay(payment, "{}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_NOT_PAYABLE"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("discrepancy")));
    }

    @Test
    void payingAHeldPaymentIsRejectedAndTellsYouToReleaseFirst() throws Exception {
        UUID payment = seedPayment(companyA, "ON_HOLD");
        pay(payment, "{}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_NOT_PAYABLE"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("release the hold first")));
    }

    @Test
    void paidIsTerminal_noFurtherTransitionExists() throws Exception {
        UUID payment = seedPayment(companyA, "READY_TO_PAY");
        pay(payment, "{}").andExpect(status().isOk());

        // Every action on a PAID payment is rejected — there is no un-pay, no hold-a-paid, no re-pay.
        pay(payment, "{}").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_NOT_PAYABLE"));
        hold(payment, "{\"reason\":\"changed my mind\"}").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_NOT_HOLDABLE"));
        releaseHold(payment, "{}").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_NOT_ON_HOLD"));
    }

    // --- Hold / Release ---

    @Test
    void holdRequiresAReason() throws Exception {
        UUID payment = seedPayment(companyA, "READY_TO_PAY");
        hold(payment, "{}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(statusOf(payment)).isEqualTo("READY_TO_PAY");
    }

    @Test
    void holdThenReleaseLandsBackOnTheCurrentVerdict() throws Exception {
        UUID payment = seedPayment(companyA, "READY_TO_PAY");

        hold(payment, "{\"reason\":\"supplier query in flight\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ON_HOLD"));
        assertThat(auditCount(payment, "HELD")).isEqualTo(1);

        // No PO legs seeded, so the re-check finds nothing to block: the payment lands back on READY_TO_PAY.
        releaseHold(payment, "{\"reason\":\"query resolved\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY_TO_PAY"));
        assertThat(auditCount(payment, "HOLD_RELEASED")).isEqualTo(1);
    }

    @Test
    void aReMatchToFailureMovesAHeldPaymentToBlocked() throws Exception {
        // Full legs, matching, so the balance is READY_TO_PAY; hold it; then break a leg and re-trigger the match.
        UUID po = fullyMatchablePo(companyA);
        UUID balance = balanceOf(po);
        assertThat(statusOf(balance)).isEqualTo("READY_TO_PAY");

        hold(balance, "{\"reason\":\"cash-flow timing\"}").andExpect(status().isOk());
        assertThat(statusOf(balance)).isEqualTo("ON_HOLD");

        // A GRN amendment short-ships the PO — the match now fails; a held payment yields to the failed verdict.
        jdbcTemplate.update(
            "UPDATE payment_grn_projection_lines SET received_quantity = 8 WHERE purchase_order_id = ?", po);
        releaseHold(balance, "{}"); // release re-checks and lands on the failing verdict

        assertThat(statusOf(balance)).isEqualTo("BLOCKED");
    }

    @Test
    void aRollingSuppliersBalanceNeverReachesReadyToPay_soNoPayButtonEverAppears() {
        // A rolling supplier's match is record-only (STATEMENT_RECORDED) — its balance is settled against the
        // statement, never per-PO, so it can't become READY_TO_PAY and Screen 6 never offers Pay/Hold on it.
        makeSupplierRolling(supplierAId, companyA); // rolling BEFORE the match runs
        UUID po = fullyMatchablePo(companyA); // seeds matching legs and drives the match once, under rolling terms
        UUID balance = balanceOf(po);

        assertThat(statusOf(balance)).isEqualTo("PENDING"); // NOT READY_TO_PAY — no payment transition under rolling
    }

    private void makeSupplierRolling(UUID supplier, UUID company) {
        UUID termId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO payment_terms (id, company_id, supplier_id, terms_type, deposit_pct, anchor_date_type, days_from_anchor, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'ROLLING', NULL, 'STATEMENT_DATE', 0, ?, ?)",
            termId, company, supplier, now, now);
        jdbcTemplate.update("UPDATE suppliers SET current_term_id = ? WHERE id = ?", termId, supplier);
    }

    @Test
    void cannotActOnAnotherCompanysPayment() throws Exception {
        UUID payment = seedPayment(companyA, "READY_TO_PAY");
        mockMvc.perform(post("/api/payments/{id}/pay", payment)
                .header(TENANT, companyB.toString()).header(USER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
    }

    // --- driving ---

    private ResultActions pay(UUID payment, String body) throws Exception {
        return action(payment, "pay", body);
    }

    private ResultActions hold(UUID payment, String body) throws Exception {
        return action(payment, "hold", body);
    }

    private ResultActions releaseHold(UUID payment, String body) throws Exception {
        return action(payment, "release-hold", body);
    }

    private ResultActions action(UUID payment, String verb, String body) throws Exception {
        return mockMvc.perform(post("/api/payments/{id}/{verb}", payment, verb)
            .header(TENANT, companyA.toString()).header(USER, userAId.toString())
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // --- seeding ---

    private UUID seedPayment(UUID company, String statusValue) {
        UUID po = seedPo(company);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO payments (id, company_id, purchase_order_id, type, amount_amount, currency, status, created_at) "
                + "VALUES (?, ?, ?, 'BALANCE', 20.00, 'USD', ?, ?)",
            id, company, po, statusValue, Timestamp.from(Instant.now()));
        return id;
    }

    private UUID seedPo(UUID company) {
        UUID po = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, ?, 'SENT', ?, ?, ?)",
            po, company.equals(companyA) ? supplierAId : supplierBId, "PO-" + po, userAId, now, company);
        return po;
    }

    /** A PO with every leg present and matching, so the match sets its balance READY_TO_PAY. */
    private UUID fullyMatchablePo(UUID company) {
        UUID po = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        UUID supplier = company.equals(companyA) ? supplierAId : supplierBId;
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, description, status, created_at, company_id) "
                + "VALUES (?, ?, 'SKU-1', 'Widget', 'ACTIVE', ?, ?)",
            sku, supplier, now, company);
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, generated_at, company_id) "
                + "VALUES (?, ?, ?, 'SENT', ?, ?, ?, ?)",
            po, supplier, "PO-" + po, userAId, now, now, company);
        jdbcTemplate.update(
            "INSERT INTO purchase_order_lines (id, company_id, purchase_order_id, sku_id, line_number, quantity, unit_price_amount, currency, price_found, created_at) "
                + "VALUES (?, ?, ?, ?, 1, 10, 2.0000, 'USD', TRUE, ?)",
            UUID.randomUUID(), company, po, sku, now);
        jdbcTemplate.update(
            "INSERT INTO payments (id, company_id, purchase_order_id, type, amount_amount, currency, status, created_at, anchor_event, days_offset) "
                + "VALUES (?, ?, ?, 'BALANCE', 20.00, 'USD', 'PENDING', ?, 'BL', 30)",
            UUID.randomUUID(), company, po, now);
        UUID piId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO proforma_invoices (id, purchase_order_id, pi_reference, currency, status, active, logged_by, created_at, company_id) "
                + "VALUES (?, ?, ?, 'USD', 'AUTO_CONFIRMED', TRUE, ?, ?, ?)",
            piId, po, "PI-" + piId, userAId, now, company);
        jdbcTemplate.update(
            "INSERT INTO proforma_invoice_lines (id, company_id, proforma_invoice_id, sku_id, line_number, confirmed_unit_price_amount, confirmed_quantity, created_at) "
                + "VALUES (?, ?, ?, ?, 1, 2.0000, 10, ?)",
            UUID.randomUUID(), company, piId, sku, now);
        jdbcTemplate.update(
            "INSERT INTO payment_grn_projection_lines (id, company_id, purchase_order_id, consignment_id, sku_id, received_quantity, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, 10, ?)",
            UUID.randomUUID(), company, po, UUID.randomUUID(), sku, now);
        jdbcTemplate.update(
            "INSERT INTO invoices (id, company_id, purchase_order_id, invoice_reference, amount_amount, currency, invoice_date, covers_type, status, active, logged_by, created_at) "
                + "VALUES (?, ?, ?, 'INV-1', 20.00, 'USD', ?, 'BALANCE', 'LOGGED', TRUE, ?, ?)",
            UUID.randomUUID(), company, po, java.sql.Date.valueOf(java.time.LocalDate.now()), userAId, now);

        drive(po, company);
        return po;
    }

    /** Run the match for a PO so its balance reaches READY_TO_PAY — the same verdict the event trigger would produce. */
    private void drive(UUID po, UUID company) {
        com.shvoy.TenantContext.set(company);
        com.shvoy.CurrentUserContext.set(userAId);
        try {
            matchService.evaluate(po);
        } finally {
            com.shvoy.TenantContext.clear();
            com.shvoy.CurrentUserContext.clear();
        }
    }

    @Autowired
    com.shvoy.payments.service.ThreeWayMatchService matchService;

    // --- assertions ---

    private UUID balanceOf(UUID po) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM payments WHERE purchase_order_id = ? AND type = 'BALANCE'", UUID.class, po);
    }

    private String statusOf(UUID paymentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    private int auditCount(UUID paymentId, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_audit_events WHERE payment_id = ? AND event_type = ?",
            Integer.class, paymentId, eventType);
    }
}
