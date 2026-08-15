package com.shvoy.shipments.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * Story 7.2 — logging shipment documents. S3 is mocked (@MockitoBean) so {@code
 * mvn test} never touches real AWS, same as PriceFileUploadControllerTest. The
 * anchor-date chain into payments has its own end-to-end test
 * ({@code ShipmentAnchorSeamTest}); this class covers capture, creation,
 * correction, retrieval, and tenancy.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "PURCHASING")
class ShipmentDocumentControllerTest {

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
    UUID userAId;
    UUID supplierAId;
    UUID supplierBId;
    UUID generatedPoA;
    UUID draftPoA;
    UUID poB;

    @BeforeEach
    void seed() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
            .thenReturn(PutObjectResponse.builder().build());

        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "admin-a@example.com", now, companyA);
        supplierAId = insertSupplier("Supplier A", companyA);
        supplierBId = insertSupplier("Supplier B", companyB);
        generatedPoA = insertPo("PO-A-GEN", supplierAId, "GENERATED", companyA);
        draftPoA = insertPo("PO-A-DRAFT", supplierAId, "DRAFT", companyA);
        poB = insertPo("PO-B", supplierBId, "SENT", companyB);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM shipment_document_audit_events WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM shipment_consignments WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM shipments WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void loggingTheFirstDocumentCreatesTheShipmentAndConsignment() throws Exception {
        mockMvc.perform(multipartBl(generatedPoA, "BL-001", "2026-08-20", null))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.blReference").value("BL-001"))
            .andExpect(jsonPath("$.blDate").value("2026-08-20"))
            .andExpect(jsonPath("$.blDocumentS3Key").isNotEmpty())
            .andExpect(jsonPath("$.consignment.purchaseOrderId").value(generatedPoA.toString()))
            .andExpect(jsonPath("$.consignment.receiptStatus").value("DOCUMENTS_PENDING"));

        assertThat(countConsignments(generatedPoA)).isEqualTo(1);
    }

    @Test
    void eachDocumentLogsIndependentlyInAnyOrder() throws Exception {
        // Packing list first — creates the shipment even though no BL exists yet.
        mockMvc.perform(multipart("/api/purchase-orders/{po}/shipment/packing-list", generatedPoA)
                .file(file("packing.pdf"))
                .param("reference", "PL-1").param("date", "2026-08-18")
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.blReference").doesNotExist())
            .andExpect(jsonPath("$.consignment.packingListReference").value("PL-1"));

        // Then the BL, onto the same shipment.
        mockMvc.perform(multipartBl(generatedPoA, "BL-001", "2026-08-20", null))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.blReference").value("BL-001"))
            .andExpect(jsonPath("$.consignment.packingListReference").value("PL-1"));

        assertThat(countConsignments(generatedPoA)).isEqualTo(1);
    }

    @Test
    void draftPurchaseOrderIsRejected() throws Exception {
        mockMvc.perform(multipartBl(draftPoA, "BL-001", "2026-08-20", null))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PO_NOT_READY_FOR_SHIPMENT"));
    }

    @Test
    void cannotLogAgainstAnotherCompanysPurchaseOrder() throws Exception {
        // Company A tries to log against company B's PO.
        mockMvc.perform(multipartBl(poB, "BL-001", "2026-08-20", null))
            .andExpect(status().isNotFound());
    }

    @Test
    void aMalformedDateIsAValidationError() throws Exception {
        mockMvc.perform(multipartBl(generatedPoA, "BL-001", "20th August", null))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void storedFileIsRetrievableByReferenceAndDownload() throws Exception {
        mockMvc.perform(multipartBl(generatedPoA, "BL-001", "2026-08-20", null))
            .andExpect(status().isCreated());

        String key = jdbcTemplate.queryForObject(
            "SELECT bl_document_s3_key FROM shipments WHERE company_id = ?", String.class, companyA);
        assertThat(key).isNotNull();

        // The download endpoint streams the stored object back.
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
            .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(),
                "BL-BYTES".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/api/purchase-orders/{po}/shipment/documents/BILL_OF_LADING", generatedPoA)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isOk())
            .andExpect(content().bytes("BL-BYTES".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void correctedBlDateIsAudited() throws Exception {
        mockMvc.perform(multipartBl(generatedPoA, "BL-001", "2026-08-20", null))
            .andExpect(status().isCreated());
        mockMvc.perform(multipartBl(generatedPoA, "BL-001", "2026-08-25", null))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.blDate").value("2026-08-25"));

        List<Map<String, Object>> corrections = jdbcTemplate.queryForList(
            "SELECT event_type, detail, created_by FROM shipment_document_audit_events "
                + "WHERE purchase_order_id = ? AND event_type = 'DOCUMENT_FIELD_CORRECTED'", generatedPoA);
        assertThat(corrections).hasSize(1);
        assertThat(corrections.get(0).get("detail").toString()).contains("2026-08-20", "2026-08-25");
        assertThat(corrections.get(0).get("created_by")).isEqualTo(userAId);
    }

    @Test
    void resubmittedFileIsSupersededAndPriorRetained() throws Exception {
        mockMvc.perform(multipartBl(generatedPoA, "BL-001", "2026-08-20", null))
            .andExpect(status().isCreated());
        String firstKey = jdbcTemplate.queryForObject(
            "SELECT bl_document_s3_key FROM shipments WHERE company_id = ?", String.class, companyA);

        mockMvc.perform(multipartBl(generatedPoA, "BL-001", "2026-08-20", null))
            .andExpect(status().isCreated());
        String secondKey = jdbcTemplate.queryForObject(
            "SELECT bl_document_s3_key FROM shipments WHERE company_id = ?", String.class, companyA);

        // A fresh key each time — the prior object is never overwritten or deleted.
        assertThat(secondKey).isNotEqualTo(firstKey);

        List<Map<String, Object>> superseded = jdbcTemplate.queryForList(
            "SELECT detail FROM shipment_document_audit_events "
                + "WHERE purchase_order_id = ? AND event_type = 'DOCUMENT_FILE_SUPERSEDED'", generatedPoA);
        assertThat(superseded).hasSize(1);
        assertThat(superseded.get(0).get("detail").toString()).contains(firstKey);
    }

    @Test
    void readingAShipmentThatDoesNotExistIs404() throws Exception {
        mockMvc.perform(get("/api/purchase-orders/{po}/shipment", generatedPoA)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isNotFound());
    }

    // --- helpers ---

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder multipartBl(
            UUID poId, String reference, String blDate, String exFactory) {
        var builder = multipart("/api/purchase-orders/{po}/shipment/bill-of-lading", poId)
            .file(file("bl.pdf"))
            .param("blReference", reference)
            .param("blDate", blDate)
            .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId);
        if (exFactory != null) {
            builder.param("exFactoryDate", exFactory);
        }
        return builder;
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, "application/pdf", ("bytes-of-" + name).getBytes(StandardCharsets.UTF_8));
    }

    private int countConsignments(UUID poId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM shipment_consignments WHERE purchase_order_id = ?", Integer.class, poId);
    }

    private UUID insertSupplier(String name, UUID companyId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            id, name, Timestamp.from(Instant.now()), companyId);
        return id;
    }

    private UUID insertPo(String number, UUID supplierId, String status, UUID companyId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, supplierId, number, status, userAId, Timestamp.from(Instant.now()), companyId);
        return id;
    }
}
