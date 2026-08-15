package com.shvoy.purchaseorders.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import com.jayway.jsonpath.JsonPath;

import com.shvoy.ConsoleEmailSender;
import com.shvoy.LogCapture;

/**
 * Story 4.7. Same conventions as PurchaseOrderGenerationControllerTest (JDBC
 * seeding, debug headers, S3Client @MockitoBean so `mvn test` never touches
 * real AWS). No class-level @Transactional.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchaseOrderSendControllerTest {

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
    UUID supplierNoEmailId;
    UUID userAId;

    @BeforeEach
    void seedCompaniesSuppliersAndUser() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);

        supplierAId = UUID.randomUUID();
        supplierBId = UUID.randomUUID();
        supplierNoEmailId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, country, contact_email, created_at, company_id) "
                + "VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?)",
            supplierAId, "Supplier A", "United Kingdom", "sales@supplier-a.example", now, companyA);
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierBId, "Supplier B", now, companyB);
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierNoEmailId, "Supplier No Email", now, companyA);

        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "admin-a@example.com", now, companyA);

        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
            .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), "%PDF-stub".getBytes()));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM purchase_order_sends WHERE company_id IN (?, ?)", companyA, companyB);
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

    private void generate(UUID poId) throws Exception {
        mockMvc.perform(post("/api/purchase-orders/{id}/generate", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
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

    private void seedPrice(UUID skuId, String amount, String currency, LocalDate validFrom, UUID companyId) {
        jdbcTemplate.update(
            "INSERT INTO sku_prices (id, sku_id, unit_price_amount, currency, valid_from, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), skuId, new BigDecimal(amount), currency, Date.valueOf(validFrom),
            Timestamp.from(Instant.now()), companyId);
    }

    private UUID seedPo(UUID supplierId, UUID companyId, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, requested_etd, created_by, "
                + "document_s3_key, created_at, company_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, supplierId, "PO-" + id, status, Date.valueOf(LocalDate.now().plusDays(7)), userAId,
            "purchase-order-documents/stub.pdf", Timestamp.from(Instant.now()), companyId);
        return id;
    }

    /** Builds a real GENERATED PO end to end via the real endpoints, for tests that need to send it. */
    private UUID createGeneratedPo(UUID supplierId) throws Exception {
        UUID poId = createPo(supplierId);
        UUID skuId = seedSku(supplierId, companyA);
        seedPrice(skuId, "2.0000", "USD", LocalDate.now().minusDays(1), companyA);
        addLine(poId, skuId, 10);
        setEtd(poId, LocalDate.now().plusDays(14));
        generate(poId);
        return poId;
    }

    // --- send: happy path ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void sendAGeneratedPoTransitionsToSentAndRecordsAudit() throws Exception {
        UUID poId = createGeneratedPo(supplierAId);

        try (LogCapture logs = new LogCapture(ConsoleEmailSender.class)) {
            mockMvc.perform(post("/api/purchase-orders/{id}/send", poId)
                    .header(TENANT_HEADER, companyA.toString())
                    .header(USER_HEADER, userAId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.sentBy").value(userAId.toString()))
                .andExpect(jsonPath("$.sentAt").exists());

            logs.firstMessageContaining("sales@supplier-a.example");
        }

        Long sendCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM purchase_order_sends WHERE purchase_order_id = ?", Long.class, poId);
        assertThat(sendCount).isEqualTo(1L);

        var row = jdbcTemplate.queryForMap(
            "SELECT sent_by, recipient_email, document_s3_key FROM purchase_order_sends WHERE purchase_order_id = ?", poId);
        assertThat(row.get("sent_by")).isEqualTo(userAId);
        assertThat(row.get("recipient_email")).isEqualTo("sales@supplier-a.example");
        assertThat(row.get("document_s3_key")).isNotNull();
    }

    // --- preconditions ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void sendingADraftPoIsRejected() throws Exception {
        UUID poId = createPo(supplierAId);

        mockMvc.perform(post("/api/purchase-orders/{id}/send", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PO_NOT_SENDABLE"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void sendingAGeneratedPoWithASupplierMissingContactEmailIsRejected() throws Exception {
        UUID poId = createGeneratedPo(supplierNoEmailId);

        mockMvc.perform(post("/api/purchase-orders/{id}/send", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SUPPLIER_MISSING_CONTACT_EMAIL"));

        Long sendCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM purchase_order_sends WHERE purchase_order_id = ?", Long.class, poId);
        assertThat(sendCount).isZero();
    }

    // --- resend ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void resendingAnAlreadySentPoSucceedsAndAppendsANewAuditRow() throws Exception {
        UUID poId = createGeneratedPo(supplierAId);

        mockMvc.perform(post("/api/purchase-orders/{id}/send", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SENT"));

        mockMvc.perform(post("/api/purchase-orders/{id}/send", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SENT"))
            .andExpect(jsonPath("$.lines[0].unitPrice.amount").value("2.0000"));

        Long sendCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM purchase_order_sends WHERE purchase_order_id = ?", Long.class, poId);
        assertThat(sendCount).isEqualTo(2L);
    }

    // --- roles/tenancy ---

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void sendIsForbiddenForReadOnlyRole() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "GENERATED");

        mockMvc.perform(post("/api/purchase-orders/{id}/send", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void sendForAnotherCompanysPoReturnsNotFound() throws Exception {
        UUID poId = seedPo(supplierBId, companyB, "GENERATED");

        mockMvc.perform(post("/api/purchase-orders/{id}/send", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
