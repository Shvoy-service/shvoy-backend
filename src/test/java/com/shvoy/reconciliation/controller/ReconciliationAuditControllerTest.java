package com.shvoy.reconciliation.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hamcrest.Matchers;
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
 * Story 5.7 — status lifecycle, the consolidated Screen 4 retrieval, and the
 * immutable audit trail, end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = {"ADMIN", "PURCHASING", "APPROVER"})
class ReconciliationAuditControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";
    private static final String USER_HEADER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID supplierAId;
    UUID creatorId;

    @BeforeEach
    void seedBaseData() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        supplierAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierAId, "Supplier A", now, companyA);
        creatorId = seedUser("PURCHASING", "ACTIVE");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM reconciliation_audit_events WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM approval_actions WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM reconciliation_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM reconciliations WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM tolerance_settings WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM approver_pool_members WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM proforma_invoice_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM proforma_invoices WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM sku_prices WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private UUID seedUser(String role, String statusValue) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id, cognito_sub) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, "u-" + id + "@example.com", role, statusValue, Timestamp.from(Instant.now()), companyA,
            UUID.randomUUID().toString());
        return id;
    }

    private UUID seedApproverInPool() {
        UUID id = seedUser("APPROVER", "ACTIVE");
        jdbcTemplate.update(
            "INSERT INTO approver_pool_members (id, user_id, created_at, company_id) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(), id, Timestamp.from(Instant.now()), companyA);
        return id;
    }

    private UUID seedSku() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, status, created_at, company_id) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
            id, supplierAId, "SKU-" + id, Timestamp.from(Instant.now()), companyA);
        return id;
    }

    private void seedPrice(UUID skuId, String amount) {
        jdbcTemplate.update(
            "INSERT INTO sku_prices (id, sku_id, unit_price_amount, currency, valid_from, valid_to, created_at, company_id) "
                + "VALUES (?, ?, ?, 'USD', ?, ?, ?, ?)",
            UUID.randomUUID(), skuId, new BigDecimal(amount),
            Date.valueOf(LocalDate.now().minusDays(30)), null, Timestamp.from(Instant.now()), companyA);
    }

    private UUID seedGeneratedPo() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO purchase_orders "
                + "(id, supplier_id, po_number, status, created_by, currency, generated_by, generated_at, created_at, company_id) "
                + "VALUES (?, ?, ?, 'GENERATED', ?, 'USD', ?, ?, ?, ?)",
            id, supplierAId, "PO-" + id, creatorId, creatorId, now, now, companyA);
        return id;
    }

    private void seedPoLine(UUID poId, UUID skuId, String unitPrice, int qty) {
        jdbcTemplate.update(
            "INSERT INTO purchase_order_lines "
                + "(id, purchase_order_id, sku_id, line_number, quantity, unit_price_amount, currency, "
                + "price_found, priced_as_of_date, created_at, company_id) "
                + "VALUES (?, ?, ?, 1, ?, ?, 'USD', true, ?, ?, ?)",
            UUID.randomUUID(), poId, skuId, qty, new BigDecimal(unitPrice),
            Date.valueOf(LocalDate.now()), Timestamp.from(Instant.now()), companyA);
    }

    private UUID logPi(UUID poId, UUID skuId, String piUnitPrice) throws Exception {
        String body = "{\"piReference\":\"SUP-REF\",\"currency\":\"USD\",\"lines\":[{\"skuId\":\"" + skuId
            + "\",\"confirmedUnitPriceAmount\":" + piUnitPrice + ",\"confirmedQuantity\":10}]}";
        MvcResult result = mockMvc.perform(post("/api/purchase-orders/{poId}/proforma-invoices", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, creatorId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    // --- consolidated retrieval + audit trail + variance-on-auto-confirm ---

    @Test
    void screen4DetailReturnsPiComparisonApprovalStateAndAuditTrail() throws Exception {
        UUID skuId = seedSku();
        seedPrice(skuId, "2.0000");
        UUID poId = seedGeneratedPo();
        seedPoLine(poId, skuId, "2.0000", 10);
        UUID piId = logPi(poId, skuId, "2.0100"); // +0.5%, within default 2% → auto-confirmed

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation-detail", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.proformaInvoice.status").value("AUTO_CONFIRMED"))
            .andExpect(jsonPath("$.reconciliation.outcome").value("AUTO_CONFIRMED"))
            .andExpect(jsonPath("$.reconciliation.toleranceApplied").value(2.00))
            // Variance is stored even on the auto-confirmed pass path (the drift-trend requirement).
            .andExpect(jsonPath("$.reconciliation.lines[0].unitPriceVariancePct").value(0.50))
            .andExpect(jsonPath("$.approvalState.requiresSignOff").value(false))
            .andExpect(jsonPath("$.auditTrail[*].eventType",
                Matchers.hasItems("PI_LOGGED", "COMPARISON_RECORDED", "AUTO_CONFIRMED")));
    }

    @Test
    void toleranceInForceAtTheTimeSurvivesALaterSettingsChange() throws Exception {
        UUID skuId = seedSku();
        seedPrice(skuId, "2.0000");
        UUID poId = seedGeneratedPo();
        seedPoLine(poId, skuId, "2.0000", 10);
        UUID piId = logPi(poId, skuId, "2.0100"); // evaluated under the default 2%

        // Change the account tolerance afterwards.
        mockMvc.perform(put("/api/reconciliation/tolerance")
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tolerancePercentage\":5.00}"))
            .andExpect(status().isOk());

        // The historical reconciliation still reports the tolerance that was in force when it was evaluated.
        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation-detail", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reconciliation.toleranceApplied").value(2.00));
    }

    @Test
    void approvalActionsAppearInTheAuditTrail() throws Exception {
        UUID approver = seedApproverInPool();
        UUID skuId = seedSku();
        seedPrice(skuId, "2.0000");
        UUID poId = seedGeneratedPo();
        seedPoLine(poId, skuId, "2.0000", 10);
        UUID piId = logPi(poId, skuId, "1.9000"); // -5% decrease → routed, single approver

        mockMvc.perform(post("/api/proforma-invoices/{piId}/approvals", piId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, approver.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"agreed with supplier\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation-detail", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(jsonPath("$.proformaInvoice.status").value("APPROVED"))
            .andExpect(jsonPath("$.auditTrail[*].eventType",
                Matchers.hasItems("PI_LOGGED", "COMPARISON_RECORDED", "ROUTED_FOR_APPROVAL", "APPROVED")));
    }

    // --- supersession → SUPERSEDED + audit, and the per-PO list ---

    @Test
    void aSupersededPiMovesToSupersededAndBothShowInThePerPoList() throws Exception {
        UUID skuId = seedSku();
        seedPrice(skuId, "2.0000");
        UUID poId = seedGeneratedPo();
        seedPoLine(poId, skuId, "2.0000", 10);

        UUID firstPi = logPi(poId, skuId, "2.0100");
        UUID secondPi = logPi(poId, skuId, "2.0200"); // corrected re-issue → supersedes the first

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation-detail", firstPi)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(jsonPath("$.proformaInvoice.status").value("SUPERSEDED"))
            .andExpect(jsonPath("$.auditTrail[*].eventType", Matchers.hasItem("SUPERSEDED")));

        mockMvc.perform(get("/api/purchase-orders/{poId}/reconciliations", poId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.proformaInvoiceId == '" + secondPi + "')].active").value(true))
            .andExpect(jsonPath("$[?(@.proformaInvoiceId == '" + firstPi + "')].status").value("SUPERSEDED"));
    }

    // --- pending-approval queue ---

    @Test
    void pendingApprovalQueueListsRoutedReconciliations() throws Exception {
        UUID skuId = seedSku();
        seedPrice(skuId, "2.0000");
        UUID poId = seedGeneratedPo();
        seedPoLine(poId, skuId, "2.0000", 10);
        UUID routedPi = logPi(poId, skuId, "1.9000"); // routed

        // A separate auto-confirmed PI on another PO should NOT appear in the queue.
        UUID sku2 = seedSku();
        seedPrice(sku2, "2.0000");
        UUID po2 = seedGeneratedPo();
        seedPoLine(po2, sku2, "2.0000", 10);
        logPi(po2, sku2, "2.0000");

        mockMvc.perform(get("/api/reconciliation/pending-approval")
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].proformaInvoiceId").value(routedPi.toString()))
            .andExpect(jsonPath("$[0].status").value("ROUTED_FOR_APPROVAL"));
    }

    // --- tenancy ---

    @Test
    void detailForAnotherCompanysPiReturnsNotFound() throws Exception {
        UUID skuId = seedSku();
        seedPrice(skuId, "2.0000");
        UUID poId = seedGeneratedPo();
        seedPoLine(poId, skuId, "2.0000", 10);
        UUID piId = logPi(poId, skuId, "2.0100");

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation-detail", piId)
                .header(TENANT_HEADER, companyB.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
