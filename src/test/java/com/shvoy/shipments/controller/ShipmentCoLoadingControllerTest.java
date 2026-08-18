package com.shvoy.shipments.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * Story 7.3 — co-loading a BL across POs. The point of these tests is
 * per-consignment <strong>independence</strong>: one supplier's packing list
 * makes only their own portion receipt-eligible, and detaching/receipting one
 * never touches a sibling. The anchor-on-attach chain into payments has its own
 * end-to-end test (ShipmentCoLoadingAnchorTest). S3 is mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "PURCHASING")
class ShipmentCoLoadingControllerTest {

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
    UUID supplierA1;
    UUID supplierA2;
    UUID supplierB;
    UUID poA1;
    UUID poA2;
    UUID poADraft;
    UUID poB;
    UUID shipmentA;

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
        supplierA1 = insertSupplier("Supplier A1", companyA);
        supplierA2 = insertSupplier("Supplier A2", companyA);
        supplierB = insertSupplier("Supplier B", companyB);
        poA1 = insertPo("PO-A1", supplierA1, "GENERATED", companyA);
        poA2 = insertPo("PO-A2", supplierA2, "GENERATED", companyA);
        poADraft = insertPo("PO-A-DRAFT", supplierA1, "DRAFT", companyA);
        poB = insertPo("PO-B", supplierB, "SENT", companyB);

        // A shipment already exists with PO-A1's consignment on it (BL not yet dated — no anchors here).
        shipmentA = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO shipments (id, bl_reference, created_at, company_id) VALUES (?, 'BL-A', ?, ?)",
            shipmentA, now, companyA);
        insertConsignment(poA1, shipmentA, "DOCUMENTS_PENDING");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM shipment_document_audit_events WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM shipment_consignments WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM shipments WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_order_audit_events WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void attachingASecondPoCreatesAConsignmentOnTheShipment() throws Exception {
        mockMvc.perform(attach(shipmentA, poA2))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.length()").value(2));

        assertThat(activeConsignmentCount(shipmentA)).isEqualTo(2);
        assertThat(auditCount(poA2, "CONSIGNMENT_ATTACHED")).isEqualTo(1);
    }

    @Test
    void duplicateAttachIsRejected() throws Exception {
        mockMvc.perform(attach(shipmentA, poA1))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PO_ALREADY_CONSIGNED"));
    }

    @Test
    void draftPurchaseOrderCannotBeAttached() throws Exception {
        mockMvc.perform(attach(shipmentA, poADraft))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PO_NOT_READY_FOR_SHIPMENT"));
    }

    @Test
    void cannotAttachAnotherCompanysPurchaseOrderEvenWithTheShipmentId() throws Exception {
        mockMvc.perform(attach(shipmentA, poB))
            .andExpect(status().isNotFound());
    }

    @Test
    void packingListsAreIsolatedBetweenSiblingsAndDriveOnlyTheirOwnEligibility() throws Exception {
        mockMvc.perform(attach(shipmentA, poA2)).andExpect(status().isCreated());

        // Log A1's packing list only.
        mockMvc.perform(multipart("/api/purchase-orders/{po}/shipment/packing-list", poA1)
                .file(file()).param("reference", "PL-A1").param("date", "2026-08-18")
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isCreated());

        // A1 is now receipt-eligible; A2 — a different supplier's portion on the same BL — is untouched.
        mockMvc.perform(get("/api/shipments/{s}/consignments", shipmentA)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.purchaseOrderId=='" + poA1 + "')].packingListLogged").value(true))
            .andExpect(jsonPath("$[?(@.purchaseOrderId=='" + poA1 + "')].receiptEligible").value(true))
            .andExpect(jsonPath("$[?(@.purchaseOrderId=='" + poA2 + "')].packingListLogged").value(false))
            .andExpect(jsonPath("$[?(@.purchaseOrderId=='" + poA2 + "')].receiptEligible").value(false));
    }

    @Test
    void detachIsAllowedAndAuditedWhilePending() throws Exception {
        mockMvc.perform(attach(shipmentA, poA2)).andExpect(status().isCreated());

        mockMvc.perform(delete("/api/shipments/{s}/consignments/{po}", shipmentA, poA2)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        assertThat(activeConsignmentCount(shipmentA)).isEqualTo(1);
        assertThat(auditCount(poA2, "CONSIGNMENT_DETACHED")).isEqualTo(1);
    }

    @Test
    void detachIsBlockedOnceReceipted() throws Exception {
        mockMvc.perform(attach(shipmentA, poA2)).andExpect(status().isCreated());
        // Simulate 7.4 having provisionally receipted A2's portion.
        jdbcTemplate.update(
            "UPDATE shipment_consignments SET receipt_status = 'PROVISIONALLY_RECEIPTED' WHERE purchase_order_id = ?",
            poA2);

        mockMvc.perform(delete("/api/shipments/{s}/consignments/{po}", shipmentA, poA2)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONSIGNMENT_NOT_DETACHABLE"));
    }

    @Test
    void listSurfacesPoSupplierAndStatusPerConsignment() throws Exception {
        mockMvc.perform(attach(shipmentA, poA2)).andExpect(status().isCreated());

        mockMvc.perform(get("/api/shipments/{s}/consignments", shipmentA)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.purchaseOrderId=='" + poA1 + "')].poNumber").value("PO-A1"))
            .andExpect(jsonPath("$[?(@.purchaseOrderId=='" + poA1 + "')].supplierName").value("Supplier A1"))
            .andExpect(jsonPath("$[?(@.purchaseOrderId=='" + poA2 + "')].supplierName").value("Supplier A2"))
            .andExpect(jsonPath("$[?(@.purchaseOrderId=='" + poA2 + "')].receiptStatus").value("DOCUMENTS_PENDING"));
    }

    // --- helpers ---

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder attach(UUID shipmentId, UUID poId) {
        return post("/api/shipments/{s}/consignments", shipmentId)
            .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"purchaseOrderId\":\"" + poId + "\"}");
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "doc.pdf", "application/pdf", "bytes".getBytes(StandardCharsets.UTF_8));
    }

    private int activeConsignmentCount(UUID shipmentId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM shipment_consignments WHERE shipment_id = ? AND detached = FALSE",
            Integer.class, shipmentId);
    }

    private int auditCount(UUID poId, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM shipment_document_audit_events WHERE purchase_order_id = ? AND event_type = ?",
            Integer.class, poId, eventType);
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

    private void insertConsignment(UUID poId, UUID shipmentId, String status) {
        jdbcTemplate.update(
            "INSERT INTO shipment_consignments (id, shipment_id, purchase_order_id, receipt_status, detached, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, FALSE, ?, ?)",
            UUID.randomUUID(), shipmentId, poId, status, Timestamp.from(Instant.now()), companyA);
    }
}
