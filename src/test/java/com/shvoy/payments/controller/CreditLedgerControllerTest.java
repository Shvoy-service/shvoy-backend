package com.shvoy.payments.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/**
 * Story 6.7 — the credit ledger endpoints. JDBC seeding, debug headers; class
 * default holds the roles that can log + cancel, overridden for the
 * authorisation tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = {"ADMIN", "PURCHASING", "FINANCE"})
class CreditLedgerControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";
    private static final String USER_HEADER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID userAId;
    UUID poAId;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "admin-a@example.com", now, companyA);
        UUID supplierId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierId, "Supplier A", now, companyA);
        poAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, 'PO-1', 'GENERATED', ?, ?, ?)",
            poAId, supplierId, userAId, now, companyA);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM credit_ledger_audit_events WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM credit_ledger_entries WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private MvcResult logCredit(String bodyJson) throws Exception {
        return mockMvc.perform(post("/api/credit-ledger")
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson))
            .andReturn();
    }

    private String shortShipment(String amount) {
        return "{\"purchaseOrderId\":\"" + poAId + "\",\"amount\":" + amount
            + ",\"currency\":\"USD\",\"cause\":\"SHORT_SHIPMENT\"}";
    }

    @Test
    void logAndListAnOpenCredit() throws Exception {
        mockMvc.perform(post("/api/credit-ledger")
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(shortShipment("50.00")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.purchaseOrderId").value(poAId.toString()))
            .andExpect(jsonPath("$.amount.amount").value("50.00"))
            .andExpect(jsonPath("$.cause").value("SHORT_SHIPMENT"))
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.loggedBy").value(userAId.toString()));

        mockMvc.perform(get("/api/credit-ledger").header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    void causeOtherRequiresDetail() throws Exception {
        mockMvc.perform(post("/api/credit-ledger")
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purchaseOrderId\":\"" + poAId + "\",\"amount\":50.00,\"currency\":\"USD\",\"cause\":\"OTHER\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void theOpenCountStatFeedsTheDashboard() throws Exception {
        logCredit(shortShipment("50.00"));
        logCredit(shortShipment("60.00"));

        mockMvc.perform(get("/api/credit-ledger/stats").header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.openCount").value(2));
    }

    @Test
    void cancellingClosesTheEntryAndRejectsBlankReasonAndDoubleCancel() throws Exception {
        UUID id = UUID.fromString(JsonPath.read(
            logCredit(shortShipment("50.00")).getResponse().getContentAsString(), "$.id"));

        // Blank reason rejected.
        mockMvc.perform(post("/api/credit-ledger/{id}/cancel", id)
                .header(TENANT_HEADER, companyA.toString()).header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
            .andExpect(status().isBadRequest());

        // Cancel with a reason.
        mockMvc.perform(post("/api/credit-ledger/{id}/cancel", id)
                .header(TENANT_HEADER, companyA.toString()).header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"waived\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.closureReason").value("waived"));

        // Cancelling again is rejected — the entry is no longer OPEN.
        mockMvc.perform(post("/api/credit-ledger/{id}/cancel", id)
                .header(TENANT_HEADER, companyA.toString()).header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CREDIT_NOT_OPEN"));
    }

    // --- authorisation & tenancy ---

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void loggingIsForbiddenForReadOnly() throws Exception {
        mockMvc.perform(post("/api/credit-ledger")
                .header(TENANT_HEADER, companyA.toString()).header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON).content(shortShipment("50.00")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void cancellingIsForbiddenForPurchasing() throws Exception {
        // Purchasing can log...
        UUID id = UUID.fromString(JsonPath.read(
            logCredit(shortShipment("50.00")).getResponse().getContentAsString(), "$.id"));
        // ...but not cancel (that's FINANCE/ADMIN).
        mockMvc.perform(post("/api/credit-ledger/{id}/cancel", id)
                .header(TENANT_HEADER, companyA.toString()).header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void getForAnotherCompanysEntryReturnsNotFound() throws Exception {
        UUID id = UUID.fromString(JsonPath.read(
            logCredit(shortShipment("50.00")).getResponse().getContentAsString(), "$.id"));

        mockMvc.perform(get("/api/credit-ledger/{id}", id).header(TENANT_HEADER, companyB.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
