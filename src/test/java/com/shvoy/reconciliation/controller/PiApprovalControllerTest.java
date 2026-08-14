package com.shvoy.reconciliation.controller;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/**
 * Story 5.5 — approval routing & the 2-of-N sign-off, end to end. Logging a PI
 * routes it (5.4); these tests drive the approve/reject actions on top. Class
 * default holds all three acting roles so one method can both log (PURCHASING)
 * and approve (APPROVER); the actor identity is the per-request
 * {@code X-Debug-User-Id}, independent of the mock role.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = {"ADMIN", "PURCHASING", "APPROVER"})
class PiApprovalControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";
    private static final String USER_HEADER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID supplierAId;
    UUID creatorId; // raises the PO and logs the PI — the self-approval subject

    @BeforeEach
    void seedBaseData() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        supplierAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierAId, "Supplier A", now, companyA);
        creatorId = seedUser(companyA, "PURCHASING", "ACTIVE");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM reconciliation_audit_events WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM approval_actions WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM reconciliation_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM reconciliations WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM approver_pool_members WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM approver_pool_settings WHERE company_id IN (?, ?)", companyA, companyB);
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

    private UUID seedUser(UUID companyId, String role, String statusValue) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id, cognito_sub) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, "u-" + id + "@example.com", role, statusValue, Timestamp.from(Instant.now()), companyId,
            UUID.randomUUID().toString());
        return id;
    }

    private UUID seedSku(UUID companyId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, status, created_at, company_id) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
            id, supplierAId, "SKU-" + id, Timestamp.from(Instant.now()), companyId);
        return id;
    }

    private void seedPrice(UUID companyId, UUID skuId, String amount) {
        jdbcTemplate.update(
            "INSERT INTO sku_prices (id, sku_id, unit_price_amount, currency, valid_from, valid_to, created_at, company_id) "
                + "VALUES (?, ?, ?, 'USD', ?, ?, ?, ?)",
            UUID.randomUUID(), skuId, new BigDecimal(amount),
            Date.valueOf(LocalDate.now().minusDays(30)), null, Timestamp.from(Instant.now()), companyId);
    }

    private UUID seedGeneratedPo(UUID companyId, UUID createdBy) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO purchase_orders "
                + "(id, supplier_id, po_number, status, created_by, currency, generated_by, generated_at, created_at, company_id) "
                + "VALUES (?, ?, ?, 'GENERATED', ?, 'USD', ?, ?, ?, ?)",
            id, supplierAId, "PO-" + id, createdBy, createdBy, now, now, companyId);
        return id;
    }

    private void seedPoLine(UUID companyId, UUID poId, UUID skuId, String unitPrice, int qty, int lineNumber) {
        jdbcTemplate.update(
            "INSERT INTO purchase_order_lines "
                + "(id, purchase_order_id, sku_id, line_number, quantity, unit_price_amount, currency, "
                + "price_found, priced_as_of_date, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'USD', true, ?, ?, ?)",
            UUID.randomUUID(), poId, skuId, lineNumber, qty, new BigDecimal(unitPrice),
            Date.valueOf(LocalDate.now()), Timestamp.from(Instant.now()), companyId);
    }

    private UUID seedApprover(UUID companyId, String statusValue, boolean inPool) {
        UUID id = seedUser(companyId, "APPROVER", statusValue);
        if (inPool) {
            jdbcTemplate.update(
                "INSERT INTO approver_pool_members (id, user_id, created_at, company_id) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), id, Timestamp.from(Instant.now()), companyId);
        }
        return id;
    }

    private String piLine(UUID skuId, String unitPrice, int qty) {
        return "{\"skuId\":\"" + skuId + "\",\"confirmedUnitPriceAmount\":" + unitPrice + ",\"confirmedQuantity\":" + qty + "}";
    }

    /** Logs a PI (as the creator) against a fresh PO; returns the routed PI id. */
    private UUID logPi(UUID poId, String... lines) throws Exception {
        String body = "{\"piReference\":\"SUP-REF\",\"currency\":\"USD\",\"lines\":[" + String.join(",", lines) + "]}";
        MvcResult result = mockMvc.perform(post("/api/purchase-orders/{poId}/proforma-invoices", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, creatorId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    /** A single-line PO at 2.0000, PI at the given confirmed price. */
    private UUID logSingleLinePi(UUID poCreator, String piUnitPrice) throws Exception {
        UUID skuId = seedSku(companyA);
        seedPrice(companyA, skuId, "2.0000");
        UUID poId = seedGeneratedPo(companyA, poCreator);
        seedPoLine(companyA, poId, skuId, "2.0000", 10, 1);
        return logPi(poId, piLine(skuId, piUnitPrice, 10));
    }

    private org.springframework.test.web.servlet.ResultActions approve(UUID piId, UUID actor) throws Exception {
        return mockMvc.perform(post("/api/proforma-invoices/{piId}/approvals", piId)
            .header(TENANT_HEADER, companyA.toString())
            .header(USER_HEADER, actor.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"comment\":\"looks fine\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions reject(UUID piId, UUID actor) throws Exception {
        return mockMvc.perform(post("/api/proforma-invoices/{piId}/rejections", piId)
            .header(TENANT_HEADER, companyA.toString())
            .header(USER_HEADER, actor.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"price not agreed with supplier\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions state(UUID piId) throws Exception {
        return mockMvc.perform(get("/api/proforma-invoices/{piId}/approval-state", piId)
            .header(TENANT_HEADER, companyA.toString()));
    }

    // --- single-approver path (a decrease) ---

    @Test
    void aDecreaseIsResolvedBySingleApprover() throws Exception {
        UUID approver = seedApprover(companyA, "ACTIVE", false); // role suffices; no pool needed
        UUID piId = logSingleLinePi(creatorId, "1.9000"); // -5% decrease → routed, no 2-of-N

        state(piId)
            .andExpect(jsonPath("$.requiresSignOff").value(false))
            .andExpect(jsonPath("$.requiredApprovals").value(1))
            .andExpect(jsonPath("$.status").value("ROUTED_FOR_APPROVAL"));

        approve(piId, approver)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.thresholdMet").value(true))
            .andExpect(jsonPath("$.approvalsCollected").value(1));
    }

    // --- 2-of-N path (an increase) ---

    @Test
    void anIncreaseRequiresTwoDistinctPoolSignOffs() throws Exception {
        UUID a = seedApprover(companyA, "ACTIVE", true);
        UUID b = seedApprover(companyA, "ACTIVE", true);
        UUID piId = logSingleLinePi(creatorId, "2.2000"); // +10% increase → 2-of-N (default N=2)

        state(piId)
            .andExpect(jsonPath("$.requiresSignOff").value(true))
            .andExpect(jsonPath("$.requiredApprovals").value(2));

        // First sign-off is insufficient — still routed.
        approve(piId, a)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ROUTED_FOR_APPROVAL"))
            .andExpect(jsonPath("$.approvalsCollected").value(1))
            .andExpect(jsonPath("$.approvalsRemaining").value(1));

        // A distinct second sign-off confirms.
        approve(piId, b)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.approvalsCollected").value(2))
            .andExpect(jsonPath("$.thresholdMet").value(true));
    }

    @Test
    void theSameApproverCannotSupplyTwoSignOffs() throws Exception {
        UUID a = seedApprover(companyA, "ACTIVE", true);
        seedApprover(companyA, "ACTIVE", true); // a second pool member exists, so N=2 is satisfiable
        UUID piId = logSingleLinePi(creatorId, "2.2000");

        approve(piId, a).andExpect(status().isOk());
        approve(piId, a)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ALREADY_SIGNED_OFF"));
    }

    @Test
    void anIncreaseApproverMustBeInThePool() throws Exception {
        UUID roleOnly = seedApprover(companyA, "ACTIVE", false); // APPROVER role, but not a pool member
        seedApprover(companyA, "ACTIVE", true);
        seedApprover(companyA, "ACTIVE", true);
        UUID piId = logSingleLinePi(creatorId, "2.2000");

        approve(piId, roleOnly)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("NOT_IN_APPROVER_POOL"));
    }

    @Test
    void anyLineBeingAnIncreaseTriggersTheGateForTheWholePi() throws Exception {
        UUID upSku = seedSku(companyA);
        seedPrice(companyA, upSku, "2.0000");
        UUID downSku = seedSku(companyA);
        seedPrice(companyA, downSku, "5.0000");
        UUID poId = seedGeneratedPo(companyA, creatorId);
        seedPoLine(companyA, poId, upSku, "2.0000", 10, 1);
        seedPoLine(companyA, poId, downSku, "5.0000", 10, 2);
        seedApprover(companyA, "ACTIVE", true);
        seedApprover(companyA, "ACTIVE", true);

        // Line 1 up +10%, line 2 down -10% → mixed, but the increase triggers the gate.
        UUID piId = logPi(poId, piLine(upSku, "2.2000", 10), piLine(downSku, "4.5000", 10));

        state(piId)
            .andExpect(jsonPath("$.requiresSignOff").value(true))
            .andExpect(jsonPath("$.requiredApprovals").value(2));
    }

    // --- self-approval ---

    @Test
    void thePoCreatorCannotApproveItsOwnPi() throws Exception {
        // The creator also holds the APPROVER role (so @PreAuthorize passes) and is in the pool.
        UUID creatorApprover = seedApprover(companyA, "ACTIVE", true);
        UUID piId = logSingleLinePi(creatorApprover, "1.9000"); // creatorApprover raised the PO and logged the PI

        approve(piId, creatorApprover)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"));
    }

    // --- rejection ---

    @Test
    void aSingleRejectionRejectsThePi() throws Exception {
        UUID approver = seedApprover(companyA, "ACTIVE", true);
        UUID piId = logSingleLinePi(creatorId, "2.2000"); // an increase, but one 'no' still stops it

        reject(piId, approver)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.actions[0].actionType").value("REJECT"))
            .andExpect(jsonPath("$.actions[0].comment").value("price not agreed with supplier"));
    }

    // --- stranded pool ---

    @Test
    void anIncreaseWithTooFewActivePoolMembersIsSurfacedNotStranded() throws Exception {
        seedApprover(companyA, "ACTIVE", true); // only ONE eligible pool member, but default N=2
        UUID piId = logSingleLinePi(creatorId, "2.2000");

        state(piId)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requiresSignOff").value(true))
            .andExpect(jsonPath("$.requiredApprovals").value(2))
            .andExpect(jsonPath("$.eligiblePoolSize").value(1))
            .andExpect(jsonPath("$.approvable").value(false))
            .andExpect(jsonPath("$.blockedReason").isNotEmpty());
    }

    // --- status guard ---

    @Test
    void approvingAnAutoConfirmedPiIsRejected() throws Exception {
        UUID approver = seedApprover(companyA, "ACTIVE", false);
        UUID piId = logSingleLinePi(creatorId, "2.0000"); // exact match → auto-confirmed, never routed

        approve(piId, approver)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PI_NOT_AWAITING_APPROVAL"));
    }

    // --- authorisation & tenancy ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void aNonApproverCannotApprove() throws Exception {
        UUID piId = logSingleLinePi(creatorId, "1.9000");

        mockMvc.perform(post("/api/proforma-invoices/{piId}/approvals", piId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, creatorId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"x\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void approvalStateForAnotherCompanysPiReturnsNotFound() throws Exception {
        UUID piId = logSingleLinePi(creatorId, "1.9000");

        mockMvc.perform(get("/api/proforma-invoices/{piId}/approval-state", piId)
                .header(TENANT_HEADER, companyB.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
