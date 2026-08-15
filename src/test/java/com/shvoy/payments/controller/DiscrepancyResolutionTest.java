package com.shvoy.payments.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.shvoy.EmailMessage;
import com.shvoy.EmailSender;

/**
 * Story 6.6 — mismatch routing & resolution, driven through the real match. A
 * mismatching invoice blocks the payment (6.5) and opens a case; the four
 * resolution paths (correct → auto-resolve, credit, override, dispute) and the
 * side-by-side are exercised end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class DiscrepancyResolutionTest {

    private static final String TENANT = "X-Debug-Company-Id";
    private static final String USER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    EmailSender emailSender;

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
        userAId = insertUser(companyA, "admin-a@example.com");
        supplierAId = insertSupplier(companyA);
        skuAId = insertSku(supplierAId, companyA);
    }

    @AfterEach
    void cleanUp() {
        for (UUID c : new UUID[] {companyA, companyB}) {
            jdbcTemplate.update("DELETE FROM discrepancy_case_audit_events WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM discrepancy_cases WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM credit_ledger_audit_events WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM credit_ledger_entries WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM invoice_covered_lines WHERE company_id = ?", c);
            // Break the self-referential correction chain (supersedes_invoice_id) before the bulk delete.
            jdbcTemplate.update("DELETE FROM invoice_match_results WHERE company_id = ?", c);
            jdbcTemplate.update("UPDATE invoices SET supersedes_invoice_id = NULL WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM invoices WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM proforma_invoice_lines WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM proforma_invoices WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM payment_audit_events WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM payment_grn_projection_lines WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM payments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM skus WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", c);
        }
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void aMismatchingInvoiceOpensACaseAndNotifiesResolvers() throws Exception {
        UUID po = legs(companyA, supplierAId, skuAId, 10, 10, 10);
        logInvoice(po, "25.00", null, null); // expected 20.00 -> block

        UUID caseId = openCaseFor(po);
        assertThat(caseId).isNotNull();
        assertThat(auditCount(caseId, "OPENED")).isEqualTo(1);
        verify(emailSender, atLeastOnce()).send(any(EmailMessage.class));
    }

    @Test
    void aReFailUpdatesTheSameCaseNotADuplicate() throws Exception {
        UUID po = legs(companyA, supplierAId, skuAId, 10, 10, 10);
        UUID invId = logInvoice(po, "25.00", null, null);
        correctInvoice(invId, "30.00", null, null); // re-fail: correct the same invoice, still a mismatch

        assertThat(caseCountFor(po)).isEqualTo(1);
        assertThat(auditCount(openCaseFor(po), "DETAIL_UPDATED")).isEqualTo(1);
    }

    @Test
    void correctingTheInvoiceAutoResolvesTheCase() throws Exception {
        UUID po = legs(companyA, supplierAId, skuAId, 10, 10, 10);
        UUID invId = logInvoice(po, "25.00", null, null);
        UUID caseId = openCaseFor(po);

        correctInvoice(invId, "20.00", null, null); // now correct -> match passes

        assertThat(statusOfCase(caseId)).isEqualTo("RESOLVED");
        assertThat(resolutionTypeOfCase(caseId)).isEqualTo("CORRECTED");
        assertThat(balanceStatus(po)).isEqualTo("READY_TO_PAY");
    }

    @Test
    void theCreditPathLinksAndResolvesWhenTheMatchPasses() throws Exception {
        UUID po = legs(companyA, supplierAId, skuAId, 10, 10, 10);
        // Invoice nets 18.00 claiming a 2.00 credit that isn't agreed yet -> block (unagreed credit).
        logInvoice(po, "18.00", "2.00", "CN-1");
        UUID caseId = openCaseFor(po);

        // Resolver agrees the 2.00 credit from the case -> re-match validates it -> pass.
        mockMvc.perform(post("/api/discrepancies/{id}/credit", caseId)
                .header(TENANT, companyA).header(USER, userAId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":2.00,\"currency\":\"USD\",\"cause\":\"DAMAGE\"}"))
            .andExpect(status().isCreated());

        assertThat(statusOfCase(caseId)).isEqualTo("RESOLVED");
        assertThat(resolutionTypeOfCase(caseId)).isEqualTo("CREDITED");
        assertThat(balanceStatus(po)).isEqualTo("READY_TO_PAY");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM credit_ledger_entries WHERE purchase_order_id = ?", String.class, po))
            .isEqualTo("APPLIED");
    }

    @Test
    @WithMockUser(roles = "FINANCE")
    void overrideByFinanceForcesReadyToPayWithAuditedReason() throws Exception {
        UUID po = legs(companyA, supplierAId, skuAId, 10, 10, 10);
        logInvoice(po, "25.00", null, null);
        UUID caseId = openCaseFor(po);

        mockMvc.perform(post("/api/discrepancies/{id}/override", caseId)
                .header(TENANT, companyA).header(USER, userAId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"rounding-level, not worth chasing\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RESOLVED"))
            .andExpect(jsonPath("$.resolutionType").value("OVERRIDDEN"));

        assertThat(balanceStatus(po)).isEqualTo("READY_TO_PAY");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT match_overridden FROM payments WHERE purchase_order_id = ? AND type = 'BALANCE'", Boolean.class, po))
            .isTrue();
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void overrideByPurchasingIsForbidden() throws Exception {
        UUID po = legs(companyA, supplierAId, skuAId, 10, 10, 10);
        logInvoice(po, "25.00", null, null);
        UUID caseId = openCaseFor(po);

        mockMvc.perform(post("/api/discrepancies/{id}/override", caseId)
                .header(TENANT, companyA).header(USER, userAId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"let it through\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void disputeHoldsTheBlock() throws Exception {
        UUID po = legs(companyA, supplierAId, skuAId, 10, 10, 10);
        logInvoice(po, "25.00", null, null);
        UUID caseId = openCaseFor(po);

        mockMvc.perform(post("/api/discrepancies/{id}/dispute", caseId)
                .header(TENANT, companyA).header(USER, userAId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"invoice contested with supplier\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISPUTED"));

        assertThat(balanceStatus(po)).isEqualTo("BLOCKED");
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void claimPutsTheResolverOnRecord() throws Exception {
        UUID po = legs(companyA, supplierAId, skuAId, 10, 10, 10);
        logInvoice(po, "25.00", null, null);
        UUID caseId = openCaseFor(po);

        mockMvc.perform(post("/api/discrepancies/{id}/claim", caseId)
                .header(TENANT, companyA).header(USER, userAId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.claimedBy").value(userAId.toString()));
    }

    @Test
    void sideBySideServesEveryLegAndOpenLedgerEntries() throws Exception {
        UUID po = legs(companyA, supplierAId, skuAId, 10, 10, 8); // short-shipped
        logInvoice(po, "20.00", null, null);
        UUID caseId = openCaseFor(po);
        // An open ledger entry that may explain the variance.
        insertOpenCredit(po, "4.00", companyA);

        mockMvc.perform(get("/api/discrepancies/{id}", caseId)
                .header(TENANT, companyA).header(USER, userAId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.poLines[0].quantity").value(10))
            .andExpect(jsonPath("$.piLines[0].quantity").value(10))
            .andExpect(jsonPath("$.grnLines[0].receivedQuantity").value(8))
            .andExpect(jsonPath("$.invoice.amount").value(20.00))
            .andExpect(jsonPath("$.openLedgerEntries.length()").value(1));
    }

    @Test
    void openCaseCountFeedsTheDashboardStat() throws Exception {
        UUID po = legs(companyA, supplierAId, skuAId, 10, 10, 10);
        logInvoice(po, "25.00", null, null);

        mockMvc.perform(get("/api/discrepancies/stats").header(TENANT, companyA).header(USER, userAId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.openCaseCount").value(1));
    }

    @Test
    void cannotViewAnotherCompanysCase() throws Exception {
        UUID po = legs(companyA, supplierAId, skuAId, 10, 10, 10);
        logInvoice(po, "25.00", null, null);
        UUID caseId = openCaseFor(po);

        mockMvc.perform(get("/api/discrepancies/{id}", caseId)
                .header(TENANT, companyB).header(USER, userAId))
            .andExpect(status().isNotFound());
    }

    // --- driving / seeding ---

    /** Logs an AMOUNT-coverage invoice (the free-standing fallback) and returns its id. */
    private UUID logInvoice(UUID po, String amount, String claimedCredit, String ref) throws Exception {
        String content = mockMvc.perform(post("/api/purchase-orders/{po}/invoices", po)
                .header(TENANT, companyA).header(USER, userAId)
                .contentType(MediaType.APPLICATION_JSON).content(invoiceBody(amount, claimedCredit, ref)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(content, "$.id"));
    }

    /**
     * Re-issue a corrected invoice against a specific one (invoice remodel: a
     * re-fail / correction is now an explicit supersession of that invoice, not a
     * second concurrent invoice on the PO).
     */
    private void correctInvoice(UUID invoiceId, String amount, String claimedCredit, String ref) throws Exception {
        mockMvc.perform(post("/api/invoices/{id}/corrections", invoiceId)
                .header(TENANT, companyA).header(USER, userAId)
                .contentType(MediaType.APPLICATION_JSON).content(invoiceBody(amount, claimedCredit, ref)))
            .andExpect(status().isCreated());
    }

    private String invoiceBody(String amount, String claimedCredit, String ref) {
        StringBuilder body = new StringBuilder("{\"invoiceReference\":\"INV\",\"amount\":").append(amount)
            .append(",\"currency\":\"USD\",\"coversType\":\"BALANCE\",\"invoiceDate\":\"").append(LocalDate.now())
            .append("\"");
        if (claimedCredit != null) {
            body.append(",\"claimedCreditAmount\":").append(claimedCredit)
                .append(",\"claimedCreditReference\":\"").append(ref).append("\"");
        }
        body.append("}");
        return body.toString();
    }

    /** PO(GEN) + line + balance payment + confirmed PI + GRN projection at the given quantities (10 @ 2.00). */
    private UUID legs(UUID company, UUID supplier, UUID sku, int poQty, int piQty, int grnQty) {
        UUID po = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, generated_at, company_id) "
                + "VALUES (?, ?, ?, 'GENERATED', ?, ?, ?, ?)",
            po, supplier, "PO-" + po, userAId, now, now, company);
        jdbcTemplate.update(
            "INSERT INTO purchase_order_lines (id, company_id, purchase_order_id, sku_id, line_number, quantity, unit_price_amount, currency, price_found, created_at) "
                + "VALUES (?, ?, ?, ?, 1, ?, 2.0000, 'USD', TRUE, ?)",
            UUID.randomUUID(), company, po, sku, poQty, now);
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
                + "VALUES (?, ?, ?, ?, 1, 2.0000, ?, ?)",
            UUID.randomUUID(), company, piId, sku, piQty, now);
        jdbcTemplate.update(
            "INSERT INTO payment_grn_projection_lines (id, company_id, purchase_order_id, consignment_id, sku_id, received_quantity, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), company, po, UUID.randomUUID(), sku, grnQty, now);
        return po;
    }

    private void insertOpenCredit(UUID po, String amount, UUID company) {
        jdbcTemplate.update(
            "INSERT INTO credit_ledger_entries (id, company_id, purchase_order_id, amount_amount, currency, cause, status, logged_by, created_at) "
                + "VALUES (?, ?, ?, ?, 'USD', 'SHORT_SHIPMENT', 'OPEN', ?, ?)",
            UUID.randomUUID(), company, po, new BigDecimal(amount), userAId, Timestamp.from(Instant.now()));
    }

    private UUID insertUser(UUID company, String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            id, email, Timestamp.from(Instant.now()), company);
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

    // --- assertions ---

    private UUID openCaseFor(UUID po) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM discrepancy_cases WHERE purchase_order_id = ? ORDER BY created_at DESC LIMIT 1",
            UUID.class, po);
    }

    private int caseCountFor(UUID po) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM discrepancy_cases WHERE purchase_order_id = ?", Integer.class, po);
    }

    private String statusOfCase(UUID caseId) {
        return jdbcTemplate.queryForObject("SELECT status FROM discrepancy_cases WHERE id = ?", String.class, caseId);
    }

    private String resolutionTypeOfCase(UUID caseId) {
        return jdbcTemplate.queryForObject(
            "SELECT resolution_type FROM discrepancy_cases WHERE id = ?", String.class, caseId);
    }

    private String balanceStatus(UUID po) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM payments WHERE purchase_order_id = ? AND type = 'BALANCE'", String.class, po);
    }

    private int auditCount(UUID caseId, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM discrepancy_case_audit_events WHERE case_id = ? AND event_type = ?",
            Integer.class, caseId, eventType);
    }
}
