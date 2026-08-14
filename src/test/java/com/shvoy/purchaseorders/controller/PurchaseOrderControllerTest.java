package com.shvoy.purchaseorders.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * Story 4.4 — create/edit/cancel a draft PO end to end. Same conventions as
 * SkuControllerTest: no class-level @Transactional, JDBC seeding, the debug
 * tenant header — plus {@code X-Debug-User-Id} (new this story), needed by
 * every mutating endpoint since {@code PurchaseOrder#createdBy} requires a
 * resolvable {@code CurrentUserContext}.
 *
 * Lines/ETD/cancel all go through {@code PurchaseOrderService#assertEditable},
 * so a single GENERATED-status PO (seeded directly — no generation endpoint
 * exists yet) is reused to prove the DRAFT-only guard across every mutation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchaseOrderControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";
    private static final String USER_HEADER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID supplierAId;
    UUID supplierBId;
    UUID userAId;

    @BeforeEach
    void seedCompaniesSuppliersAndUser() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);

        supplierAId = UUID.randomUUID();
        supplierBId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierAId, "Supplier A", now, companyA);
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierBId, "Supplier B", now, companyB);

        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "admin-a@example.com", now, companyA);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM po_number_counters WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM sku_prices WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private UUID createPo(UUID supplierId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/purchase-orders")
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"supplierId\":\"" + supplierId + "\"}"))
            .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private UUID addLine(UUID poId, UUID skuId, int quantity) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/purchase-orders/{id}/lines", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuId\":\"" + skuId + "\",\"quantity\":" + quantity + "}"))
            .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.lines[0].id"));
    }

    private UUID seedPo(UUID supplierId, UUID companyId, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, supplierId, "PO-" + id, status, userAId, Timestamp.from(Instant.now()), companyId);
        return id;
    }

    private UUID seedSku(UUID supplierId, UUID companyId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, status, created_at, company_id) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
            id, supplierId, "SKU-" + id, Timestamp.from(Instant.now()), companyId);
        return id;
    }

    private void seedPrice(UUID skuId, String amount, String currency, UUID companyId) {
        jdbcTemplate.update(
            "INSERT INTO sku_prices (id, sku_id, unit_price_amount, currency, valid_from, valid_to, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), skuId, new BigDecimal(amount), currency,
            Date.valueOf(LocalDate.now().minusDays(1)), null, Timestamp.from(Instant.now()), companyId);
    }

    // --- create ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void createAssignsPoNumberAndCreatedByAndDefaultsToDraft() throws Exception {
        mockMvc.perform(post("/api/purchase-orders")
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"supplierId\":\"" + supplierAId + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.poNumber").value("PO-0001"))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.supplierId").value(supplierAId.toString()))
            .andExpect(jsonPath("$.createdBy").value(userAId.toString()))
            .andExpect(jsonPath("$.lines").isEmpty())
            .andExpect(jsonPath("$.orderTotal").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void createForAnotherCompanysSupplierReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/purchase-orders")
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"supplierId\":\"" + supplierBId + "\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void createIsForbiddenForReadOnlyRole() throws Exception {
        mockMvc.perform(post("/api/purchase-orders")
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"supplierId\":\"" + supplierAId + "\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // --- lines ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void addLineInvokesPricingAndRecomputesTotals() throws Exception {
        UUID poId = createPo(supplierAId);
        UUID skuId = seedSku(supplierAId, companyA);
        seedPrice(skuId, "2.0000", "GBP", companyA);

        mockMvc.perform(post("/api/purchase-orders/{id}/lines", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuId\":\"" + skuId + "\",\"quantity\":5}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.lines[0].skuId").value(skuId.toString()))
            .andExpect(jsonPath("$.lines[0].lineNumber").value(1))
            .andExpect(jsonPath("$.lines[0].quantity").value(5))
            .andExpect(jsonPath("$.lines[0].unitPrice.amount").value("2.0000"))
            .andExpect(jsonPath("$.lines[0].lineTotal.amount").value("10.00"))
            .andExpect(jsonPath("$.orderTotal.amount").value("10.00"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void updateLineRepricesAndRecomputesTotals() throws Exception {
        UUID poId = createPo(supplierAId);
        UUID skuId = seedSku(supplierAId, companyA);
        seedPrice(skuId, "2.0000", "GBP", companyA);
        UUID lineId = addLine(poId, skuId, 5);

        UUID sku2Id = seedSku(supplierAId, companyA);
        seedPrice(sku2Id, "3.0000", "GBP", companyA);

        mockMvc.perform(put("/api/purchase-orders/{id}/lines/{lineId}", poId, lineId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuId\":\"" + sku2Id + "\",\"quantity\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines[0].skuId").value(sku2Id.toString()))
            .andExpect(jsonPath("$.lines[0].quantity").value(2))
            .andExpect(jsonPath("$.lines[0].unitPrice.amount").value("3.0000"))
            .andExpect(jsonPath("$.lines[0].lineTotal.amount").value("6.00"))
            .andExpect(jsonPath("$.orderTotal.amount").value("6.00"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void removeLineRecomputesTotalsBackToNullWhenNoLinesRemain() throws Exception {
        UUID poId = createPo(supplierAId);
        UUID skuId = seedSku(supplierAId, companyA);
        seedPrice(skuId, "2.0000", "GBP", companyA);
        UUID lineId = addLine(poId, skuId, 5);

        mockMvc.perform(delete("/api/purchase-orders/{id}/lines/{lineId}", poId, lineId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines").isEmpty())
            .andExpect(jsonPath("$.orderTotal").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void addLineToANonDraftPoReturnsPoNotEditable() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "GENERATED");
        UUID skuId = seedSku(supplierAId, companyA);

        mockMvc.perform(post("/api/purchase-orders/{id}/lines", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuId\":\"" + skuId + "\",\"quantity\":5}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PO_NOT_EDITABLE"));
    }

    // --- requested ETD ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void setRequestedEtdUpdatesTheDraft() throws Exception {
        UUID poId = createPo(supplierAId);
        String future = LocalDate.now().plusDays(7).toString();

        mockMvc.perform(put("/api/purchase-orders/{id}/etd", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestedEtd\":\"" + future + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requestedEtd").value(future));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void setRequestedEtdWithPastDateReturnsValidationError() throws Exception {
        UUID poId = createPo(supplierAId);
        String past = LocalDate.now().minusDays(1).toString();

        mockMvc.perform(put("/api/purchase-orders/{id}/etd", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestedEtd\":\"" + past + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void setRequestedEtdOnANonDraftPoReturnsPoNotEditable() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "SENT");
        String future = LocalDate.now().plusDays(7).toString();

        mockMvc.perform(put("/api/purchase-orders/{id}/etd", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestedEtd\":\"" + future + "\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PO_NOT_EDITABLE"));
    }

    // --- cancel ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void cancelDraftPoSoftDeletesIt() throws Exception {
        UUID poId = createPo(supplierAId);

        mockMvc.perform(delete("/api/purchase-orders/{id}", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void cancelANonDraftPoReturnsPoNotEditable() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "GENERATED");

        mockMvc.perform(delete("/api/purchase-orders/{id}", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PO_NOT_EDITABLE"));
    }

    // --- reads (open to any authenticated role) ---

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void getReturnsOwnPurchaseOrder() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "DRAFT");

        mockMvc.perform(get("/api/purchase-orders/{id}", poId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(poId.toString()))
            .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void getForAnotherCompanysPurchaseOrderReturnsNotFound() throws Exception {
        UUID poId = seedPo(supplierBId, companyB, "DRAFT");

        mockMvc.perform(get("/api/purchase-orders/{id}", poId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void listFiltersByStatusAndTenant() throws Exception {
        seedPo(supplierAId, companyA, "DRAFT");
        UUID generatedId = seedPo(supplierAId, companyA, "GENERATED");
        seedPo(supplierBId, companyB, "DRAFT");

        mockMvc.perform(get("/api/purchase-orders")
                .header(TENANT_HEADER, companyA.toString())
                .param("status", "GENERATED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(generatedId.toString()));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void listWithoutStatusReturnsAllOfTheCallersOwnPurchaseOrders() throws Exception {
        seedPo(supplierAId, companyA, "DRAFT");
        seedPo(supplierAId, companyA, "GENERATED");
        seedPo(supplierBId, companyB, "DRAFT");

        mockMvc.perform(get("/api/purchase-orders")
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
    }
}
