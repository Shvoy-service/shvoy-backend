package com.shvoy.purchaseorders.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.MvcResult;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import com.jayway.jsonpath.JsonPath;

/**
 * Story 4.6. Same conventions as PurchaseOrderControllerTest (JDBC seeding,
 * debug headers) plus PriceFileUploadControllerTest's S3 mocking (@MockitoBean
 * — `mvn test` never touches real AWS). No class-level @Transactional.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchaseOrderGenerationControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";
    private static final String USER_HEADER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    S3Client s3Client;

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
            "INSERT INTO suppliers (id, name, status, country, contact_email, created_at, company_id) "
                + "VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?)",
            supplierAId, "Supplier A", "United Kingdom", "sales@supplier-a.example", now, companyA);
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierBId, "Supplier B", now, companyB);

        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "admin-a@example.com", now, companyA);

        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
            .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), "%PDF-stub".getBytes()));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM purchase_order_price_override_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_order_price_overrides WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM payments WHERE company_id IN (?, ?)", companyA, companyB);
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

    private void addLine(UUID poId, UUID skuId, int quantity) throws Exception {
        mockMvc.perform(post("/api/purchase-orders/{id}/lines", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuId\":\"" + skuId + "\",\"quantity\":" + quantity + "}"))
            .andExpect(status().isCreated());
    }

    private void setEtd(UUID poId, LocalDate etd) throws Exception {
        mockMvc.perform(put("/api/purchase-orders/{id}/etd", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestedEtd\":\"" + etd + "\"}"))
            .andExpect(status().isOk());
    }

    private UUID seedSku(UUID supplierId, UUID companyId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, description, status, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
            id, supplierId, "SKU-" + id, "Widget", Timestamp.from(Instant.now()), companyId);
        return id;
    }

    private void seedPrice(UUID skuId, String amount, String currency, LocalDate validFrom, LocalDate validTo, UUID companyId) {
        jdbcTemplate.update(
            "INSERT INTO sku_prices (id, sku_id, unit_price_amount, currency, valid_from, valid_to, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), skuId, new BigDecimal(amount), currency, Date.valueOf(validFrom),
            validTo == null ? null : Date.valueOf(validTo), Timestamp.from(Instant.now()), companyId);
    }

    private UUID seedPo(UUID supplierId, UUID companyId, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, requested_etd, created_by, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, supplierId, "PO-" + id, status, Date.valueOf(LocalDate.now().plusDays(7)), userAId,
            Timestamp.from(Instant.now()), companyId);
        return id;
    }

    // --- generate: happy path ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void generateACleanDraftProducesStoresAndTransitionsStatus() throws Exception {
        UUID poId = createPo(supplierAId);
        UUID skuId = seedSku(supplierAId, companyA);
        seedPrice(skuId, "2.0000", "USD", LocalDate.now().minusDays(1), null, companyA);
        addLine(poId, skuId, 10);
        setEtd(poId, LocalDate.now().plusDays(14));

        mockMvc.perform(post("/api/purchase-orders/{id}/generate", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("GENERATED"))
            .andExpect(jsonPath("$.generatedBy").value(userAId.toString()))
            .andExpect(jsonPath("$.generatedAt").exists())
            .andExpect(jsonPath("$.orderTotal.amount").value("20.00"));

        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));

        mockMvc.perform(get("/api/purchase-orders/{id}/document", poId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void documentBeforeGenerationReturnsNotFound() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "DRAFT");

        mockMvc.perform(get("/api/purchase-orders/{id}/document", poId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // --- preconditions ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void generateWithNoLinesIsBlocked() throws Exception {
        UUID poId = createPo(supplierAId);
        setEtd(poId, LocalDate.now().plusDays(14));

        mockMvc.perform(post("/api/purchase-orders/{id}/generate", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PO_NOT_READY_TO_GENERATE"));

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void generateWithNoEtdSetIsBlocked() throws Exception {
        UUID poId = createPo(supplierAId);
        UUID skuId = seedSku(supplierAId, companyA);
        seedPrice(skuId, "2.0000", "USD", LocalDate.now().minusDays(1), null, companyA);
        addLine(poId, skuId, 10);

        mockMvc.perform(post("/api/purchase-orders/{id}/generate", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PO_NOT_READY_TO_GENERATE"));
    }

    // --- the 4.5 gate ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void generateWithAnExpiredPriceLineAndNoOverrideIsBlocked() throws Exception {
        UUID poId = createPo(supplierAId);
        UUID skuId = seedSku(supplierAId, companyA);
        seedPrice(skuId, "2.0000", "USD", LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31), companyA);
        addLine(poId, skuId, 10);
        setEtd(poId, LocalDate.now().plusDays(14));

        mockMvc.perform(post("/api/purchase-orders/{id}/generate", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PO_HAS_EXPIRED_PRICES"));

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void generateWithAnExpiredPriceLineAndAValidOverrideSucceedsUsingTheManualPrice() throws Exception {
        UUID poId = createPo(supplierAId);
        UUID skuId = seedSku(supplierAId, companyA);
        seedPrice(skuId, "2.0000", "USD", LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31), companyA);
        addLine(poId, skuId, 10);
        setEtd(poId, LocalDate.now().plusDays(14));

        MvcResult lineResult = mockMvc.perform(get("/api/purchase-orders/{id}", poId)
                .header(TENANT_HEADER, companyA.toString()))
            .andReturn();
        UUID lineId = UUID.fromString(JsonPath.read(lineResult.getResponse().getContentAsString(), "$.lines[0].id"));

        mockMvc.perform(post("/api/purchase-orders/{id}/generate", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"override\":{\"reason\":\"Supplier confirmed by phone\",\"lines\":"
                    + "[{\"lineId\":\"" + lineId + "\",\"unitPriceAmount\":5.0000,\"currency\":\"USD\"}]}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("GENERATED"))
            .andExpect(jsonPath("$.lines[0].unitPrice.amount").value("5.0000"))
            .andExpect(jsonPath("$.orderTotal.amount").value("50.00"));

        Long overrideCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM purchase_order_price_overrides WHERE purchase_order_id = ?", Long.class, poId);
        assertThat(overrideCount).isEqualTo(1L);
    }

    // --- status guard ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void generatingAnAlreadyGeneratedPoIsRejected() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "GENERATED");

        mockMvc.perform(post("/api/purchase-orders/{id}/generate", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PO_NOT_EDITABLE"));
    }

    // --- roles/tenancy ---

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void generateIsForbiddenForReadOnlyRole() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "DRAFT");

        mockMvc.perform(post("/api/purchase-orders/{id}/generate", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void generateForAnotherCompanysPoReturnsNotFound() throws Exception {
        UUID poId = seedPo(supplierBId, companyB, "DRAFT");

        mockMvc.perform(post("/api/purchase-orders/{id}/generate", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void documentForAnotherCompanysPoReturnsNotFound() throws Exception {
        UUID poId = seedPo(supplierBId, companyB, "GENERATED");

        mockMvc.perform(get("/api/purchase-orders/{id}/document", poId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // --- price snapshot is locked after generation ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void generatedPoPricesAreUnaffectedByALaterPriceFileChange() throws Exception {
        UUID poId = createPo(supplierAId);
        UUID skuId = seedSku(supplierAId, companyA);
        seedPrice(skuId, "2.0000", "USD", LocalDate.now().minusDays(1), null, companyA);
        addLine(poId, skuId, 10);
        setEtd(poId, LocalDate.now().plusDays(14));

        mockMvc.perform(post("/api/purchase-orders/{id}/generate", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines[0].unitPrice.amount").value("2.0000"));

        // Simulate a new price file superseding the one this PO was generated against.
        jdbcTemplate.update("UPDATE sku_prices SET valid_to = ? WHERE sku_id = ?",
            Date.valueOf(LocalDate.now()), skuId);
        seedPrice(skuId, "9.0000", "USD", LocalDate.now().plusDays(1), null, companyA);

        mockMvc.perform(get("/api/purchase-orders/{id}", poId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines[0].unitPrice.amount").value("2.0000"))
            .andExpect(jsonPath("$.orderTotal.amount").value("20.00"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void cancellingOrEditingAGeneratedPoIsRejected() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "GENERATED");

        mockMvc.perform(delete("/api/purchase-orders/{id}", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PO_NOT_EDITABLE"));
    }
}
