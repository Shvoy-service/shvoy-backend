package com.shvoy.payments.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/**
 * Story 6.4 — invoice logging. Mirrors ProformaInvoiceControllerTest's
 * conventions plus the anchor-date trigger (the first real caller of 6.2's
 * seam). No class-level @Transactional; JDBC seeding; debug headers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = {"ADMIN", "PURCHASING", "FINANCE"})
class InvoiceControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";
    private static final String USER_HEADER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID supplierAId;
    UUID userAId;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        supplierAId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierAId, "Supplier A", now, companyA);
        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "admin-a@example.com", now, companyA);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM payment_audit_events WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM payments WHERE company_id IN (?, ?)", companyA, companyB);
        // Break the self-referential correction chain (supersedes_invoice_id) before the bulk delete.
        jdbcTemplate.update("DELETE FROM invoice_match_results WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("UPDATE invoices SET supersedes_invoice_id = NULL WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM invoice_covered_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM invoices WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private UUID seedPo(UUID supplierId, UUID companyId, String statusValue) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, supplierId, "PO-" + id, statusValue, userAId, Timestamp.from(Instant.now()), companyId);
        return id;
    }

    private UUID seedInvoiceAnchoredBalance(UUID companyId, UUID poId, int daysOffset) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO payments (id, purchase_order_id, type, amount_amount, currency, due_date, anchor_event, "
                + "days_offset, status, created_at, company_id) "
                + "VALUES (?, ?, 'BALANCE', 70.00, 'USD', NULL, 'INVOICE', ?, 'PENDING', ?, ?)",
            id, poId, daysOffset, Timestamp.from(Instant.now()), companyId);
        return id;
    }

    /** AMOUNT coverage — the free-standing fallback, always coherent (no references to validate). */
    private String body(String reference, String amount, String currency, String date) {
        return "{\"invoiceReference\":\"" + reference + "\",\"amount\":" + amount + ",\"currency\":\"" + currency
            + "\",\"invoiceDate\":\"" + date + "\",\"coversType\":\"AMOUNT\"}";
    }

    private MvcResult logInvoice(UUID poId, String reqBody) throws Exception {
        return mockMvc.perform(post("/api/purchase-orders/{poId}/invoices", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(reqBody))
            .andReturn();
    }

    private MvcResult correctInvoice(UUID invoiceId, String reqBody) throws Exception {
        return mockMvc.perform(post("/api/invoices/{id}/corrections", invoiceId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(reqBody))
            .andReturn();
    }

    private LocalDate dueDateOf(UUID paymentId) {
        java.sql.Date d = jdbcTemplate.queryForObject(
            "SELECT due_date FROM payments WHERE id = ?", java.sql.Date.class, paymentId);
        return d == null ? null : d.toLocalDate();
    }

    // --- logging ---

    @Test
    void logAValidInvoiceAgainstAGeneratedPo() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "GENERATED");

        mockMvc.perform(post("/api/purchase-orders/{poId}/invoices", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"invoiceReference\":\"INV-9001\",\"amount\":1234.56,\"currency\":\"USD\","
                    + "\"invoiceDate\":\"2026-03-01\",\"claimedCreditAmount\":50.00,\"claimedCreditReference\":\"CN-1\","
                    + "\"coversType\":\"AMOUNT\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.purchaseOrderId").value(poId.toString()))
            .andExpect(jsonPath("$.invoiceReference").value("INV-9001"))
            .andExpect(jsonPath("$.amount.amount").value("1234.56"))
            .andExpect(jsonPath("$.amount.currency").value("USD"))
            .andExpect(jsonPath("$.invoiceDate").value("2026-03-01"))
            .andExpect(jsonPath("$.claimedCredit.amount").value("50.00"))
            .andExpect(jsonPath("$.claimedCreditReference").value("CN-1"))
            .andExpect(jsonPath("$.status").value("LOGGED"))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.coversType").value("AMOUNT"))
            .andExpect(jsonPath("$.weakestSignal").value(true))
            .andExpect(jsonPath("$.loggedBy").value(userAId.toString()));
    }

    @Test
    void logAgainstADraftPoIsRejected() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "DRAFT");

        logInvoiceThenExpect(poId, body("INV-1", "100.00", "USD", "2026-03-01"), 409, "PO_NOT_READY_FOR_INVOICE");
    }

    @Test
    void anAmountDisagreeingWithThePoIsRecordedNotRejected() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "SENT");
        // No PO-amount comparison at entry — a wildly different invoice total is still recorded (the match judges it).
        logInvoice(poId, body("INV-1", "999999.99", "USD", "2026-03-01"))
            .getResponse();
        mockMvc.perform(post("/api/purchase-orders/{poId}/invoices", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("INV-2", "999999.99", "USD", "2026-03-01")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.amount.amount").value("999999.99"));
    }

    @Test
    void aDifferentCurrencyIsRecordedNotConverted() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "GENERATED");

        mockMvc.perform(post("/api/purchase-orders/{poId}/invoices", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("INV-1", "500.00", "GBP", "2026-03-01")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.amount.currency").value("GBP")); // recorded as GBP, no FX
    }

    // --- many-per-PO: logging no longer supersedes (invoice remodel) ---

    @Test
    void aSecondInvoiceDoesNotSupersedeTheFirst() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "GENERATED");
        UUID firstId = UUID.fromString(JsonPath.read(
            logInvoice(poId, body("INV-1", "100.00", "USD", "2026-03-01")).getResponse().getContentAsString(), "$.id"));
        UUID secondId = UUID.fromString(JsonPath.read(
            logInvoice(poId, body("INV-2", "110.00", "USD", "2026-03-05")).getResponse().getContentAsString(), "$.id"));

        // Both stay active — a PO can carry many concurrent invoices now.
        mockMvc.perform(get("/api/invoices/{id}", firstId).header(TENANT_HEADER, companyA.toString()))
            .andExpect(jsonPath("$.status").value("LOGGED"))
            .andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(get("/api/invoices/{id}", secondId).header(TENANT_HEADER, companyA.toString()))
            .andExpect(jsonPath("$.status").value("LOGGED"))
            .andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(get("/api/purchase-orders/{poId}/invoices", poId).header(TENANT_HEADER, companyA.toString()))
            .andExpect(jsonPath("$.length()").value(2));
    }

    // --- correction is the explicit supersession path ---

    @Test
    void correctingAnInvoiceSupersedesThatSpecificInvoice() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "GENERATED");
        UUID firstId = UUID.fromString(JsonPath.read(
            logInvoice(poId, body("INV-1", "100.00", "USD", "2026-03-01")).getResponse().getContentAsString(), "$.id"));
        UUID correctionId = UUID.fromString(JsonPath.read(
            correctInvoice(firstId, body("INV-1B", "105.00", "USD", "2026-03-02")).getResponse().getContentAsString(),
            "$.id"));

        mockMvc.perform(get("/api/invoices/{id}", firstId).header(TENANT_HEADER, companyA.toString()))
            .andExpect(jsonPath("$.status").value("SUPERSEDED"))
            .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(get("/api/invoices/{id}", correctionId).header(TENANT_HEADER, companyA.toString()))
            .andExpect(jsonPath("$.status").value("LOGGED"))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.supersedesInvoiceId").value(firstId.toString()));
    }

    // --- the anchor-date trigger (first real caller of 6.2's seam) ---

    @Test
    void loggingAnInvoiceSetsTheDueDateOfAnInvoiceAnchoredBalance() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "GENERATED");
        UUID balanceId = seedInvoiceAnchoredBalance(companyA, poId, 30); // due = invoice date + 30

        logInvoice(poId, body("INV-1", "100.00", "USD", "2026-03-01"));

        assertDueDate(balanceId, LocalDate.of(2026, 3, 31));
    }

    @Test
    void correctingTheFirstInvoiceWithANewDateRecalculatesTheDueDate() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "GENERATED");
        UUID balanceId = seedInvoiceAnchoredBalance(companyA, poId, 30);

        UUID firstId = UUID.fromString(JsonPath.read(
            logInvoice(poId, body("INV-1", "100.00", "USD", "2026-03-01")).getResponse().getContentAsString(), "$.id"));
        assertDueDate(balanceId, LocalDate.of(2026, 3, 31));

        // A correction of the first (anchoring) invoice with a later date re-fires the anchor; 6.2 recalculates.
        correctInvoice(firstId, body("INV-1B", "100.00", "USD", "2026-03-10"));
        assertDueDate(balanceId, LocalDate.of(2026, 4, 9));
    }

    @Test
    void aSecondIndependentInvoiceDoesNotMoveTheAnchorTheFirstSet() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "GENERATED");
        UUID balanceId = seedInvoiceAnchoredBalance(companyA, poId, 30);

        logInvoice(poId, body("INV-1", "100.00", "USD", "2026-03-01"));
        assertDueDate(balanceId, LocalDate.of(2026, 3, 31));

        // A brand-new second invoice is NOT the first non-deposit invoice — the anchor policy leaves the date put.
        logInvoice(poId, body("INV-2", "100.00", "USD", "2026-03-10"));
        assertDueDate(balanceId, LocalDate.of(2026, 3, 31));
    }

    // --- authorisation & tenancy ---

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void loggingIsForbiddenForReadOnly() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "GENERATED");

        logInvoiceThenExpect(poId, body("INV-1", "100.00", "USD", "2026-03-01"), 403, "FORBIDDEN");
    }

    @Test
    void getForAnotherCompanysInvoiceReturnsNotFound() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "GENERATED");
        UUID invoiceId = UUID.fromString(JsonPath.read(
            logInvoice(poId, body("INV-1", "100.00", "USD", "2026-03-01")).getResponse().getContentAsString(), "$.id"));

        mockMvc.perform(get("/api/invoices/{id}", invoiceId).header(TENANT_HEADER, companyB.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private void logInvoiceThenExpect(UUID poId, String reqBody, int httpStatus, String code) throws Exception {
        mockMvc.perform(post("/api/purchase-orders/{poId}/invoices", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(reqBody))
            .andExpect(status().is(httpStatus))
            .andExpect(jsonPath("$.code").value(code));
    }

    private void assertDueDate(UUID paymentId, LocalDate expected) {
        org.assertj.core.api.Assertions.assertThat(dueDateOf(paymentId)).isEqualTo(expected);
    }
}
