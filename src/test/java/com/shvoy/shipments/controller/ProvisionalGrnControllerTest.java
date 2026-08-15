package com.shvoy.shipments.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import com.shvoy.payments.event.ProvisionalGoodsReceiptEvent;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * Story 7.4 — the provisional GRN. Covers the document gate, the per-SKU
 * quantity snapshot (the substance), the explicit-action + arrival-not-required
 * stance, the Feature 7 → 6 event, amendment auditing, snapshot immutability
 * against a later packing-list correction, sibling independence, and tenancy.
 * S3 is mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "PURCHASING")
@RecordApplicationEvents
class ProvisionalGrnControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";
    private static final String USER_HEADER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ApplicationEvents applicationEvents;

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
    UUID poB;
    UUID skuA1;
    UUID skuA2;

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
        poB = insertPo("PO-B", supplierB, "SENT", companyB);
        skuA1 = insertSku(supplierA1);
        skuA2 = insertSku(supplierA2);
    }

    @AfterEach
    void cleanUp() {
        // Creating a provisional GRN publishes an event that payments projects (6.5) — clean that up first (FK to purchase_orders).
        jdbcTemplate.update("DELETE FROM payment_grn_projection_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM shipment_goods_receipt_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM shipment_packing_list_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM inspection_reports WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM shipment_document_audit_events WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM shipment_consignments WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM shipments WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void eligibleConsignmentReceiptsSnapshottingQuantitiesAndPublishingTheEvent() throws Exception {
        logBl(poA1, "2026-09-01");
        logPackingList(poA1, "PL-A1", skuA1, 10);

        mockMvc.perform(createGrn(poA1))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.exists").value(true))
            .andExpect(jsonPath("$.receiptStatus").value("PROVISIONALLY_RECEIPTED"))
            .andExpect(jsonPath("$.provenance").value("INSPECTION_NOT_DUE"))
            .andExpect(jsonPath("$.qcFailed").value(false))
            .andExpect(jsonPath("$.lines.length()").value(1))
            .andExpect(jsonPath("$.lines[0].skuId").value(skuA1.toString()))
            .andExpect(jsonPath("$.lines[0].receivedQuantity").value(10));

        // The Feature 7 -> 6 seam fired, carrying the received quantities.
        List<ProvisionalGoodsReceiptEvent> events =
            applicationEvents.stream(ProvisionalGoodsReceiptEvent.class).toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).purchaseOrderId()).isEqualTo(poA1);
        assertThat(events.get(0).receivedLines()).singleElement()
            .satisfies(l -> {
                assertThat(l.skuId()).isEqualTo(skuA1);
                assertThat(l.receivedQuantity()).isEqualTo(10);
            });
    }

    @Test
    void physicalArrivalIsNotRequired() throws Exception {
        logBl(poA1, "2026-09-01");
        logPackingList(poA1, "PL-A1", skuA1, 10);
        mockMvc.perform(createGrn(poA1)).andExpect(status().isCreated());

        // Receipted with no arrival date whatsoever — the roadmap's stance, pinned.
        String status = jdbcTemplate.queryForObject(
            "SELECT receipt_status FROM shipment_consignments WHERE purchase_order_id = ?", String.class, poA1);
        java.sql.Date arrival = jdbcTemplate.queryForObject(
            "SELECT arrival_date FROM shipment_consignments WHERE purchase_order_id = ?", java.sql.Date.class, poA1);
        assertThat(status).isEqualTo("PROVISIONALLY_RECEIPTED");
        assertThat(arrival).isNull();
    }

    @Test
    void missingPackingListBlocksWithNaming() throws Exception {
        logBl(poA1, "2026-09-01"); // BL only, no packing list
        mockMvc.perform(createGrn(poA1))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONSIGNMENT_NOT_RECEIPT_ELIGIBLE"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("packing list")));
    }

    @Test
    void missingBillOfLadingBlocksWithNaming() throws Exception {
        logPackingList(poA1, "PL-A1", skuA1, 10); // packing list only, no BL
        mockMvc.perform(createGrn(poA1))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONSIGNMENT_NOT_RECEIPT_ELIGIBLE"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Bill of Lading")));
    }

    @Test
    void notDueReceiptsOnBlAndPackingListAloneFlaggedNotDue() throws Exception {
        // A company that never touches inspection fields: BL + packing list is enough, flagged inspection_not_due.
        logBl(poA1, "2026-09-01");
        logPackingList(poA1, "PL-A1", skuA1, 10);
        mockMvc.perform(createGrn(poA1))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.provenance").value("INSPECTION_NOT_DUE"));
    }

    @Test
    void inspectionDueWithoutReportBlocksAsInspectionPending() throws Exception {
        logBl(poA1, "2026-09-01");
        logPackingList(poA1, "PL-A1", skuA1, 10);
        setInspectionDue(poA1, true, null);

        mockMvc.perform(createGrn(poA1))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONSIGNMENT_INSPECTION_PENDING"));
    }

    @Test
    void inspectionDueWithPassReceiptsClean() throws Exception {
        logBl(poA1, "2026-09-01");
        logPackingList(poA1, "PL-A1", skuA1, 10);
        setInspectionDue(poA1, true, null);
        logInspection(poA1, "INS-1", "PASS");

        mockMvc.perform(createGrn(poA1))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.provenance").value("CLEAN"))
            .andExpect(jsonPath("$.qcFailed").value(false));
    }

    @Test
    void reworkBlocksTheGrnThenAPassReleasesAndItCreates() throws Exception {
        logBl(poA1, "2026-09-01");
        logPackingList(poA1, "PL-A1", skuA1, 10);
        logInspection(poA1, "INS-1", "REWORK_REQUIRED"); // held at factory — nothing shipped

        assertThat(consignmentStatus(poA1)).isEqualTo("REWORK_REQUIRED");
        mockMvc.perform(createGrn(poA1))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONSIGNMENT_IN_REWORK_HOLD"));

        // Reworked goods pass re-inspection → hold released → GRN creates.
        logInspection(poA1, "INS-2", "PASS");
        assertThat(consignmentStatus(poA1)).isEqualTo("DOCUMENTS_PENDING");
        mockMvc.perform(createGrn(poA1))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.provenance").value("CLEAN"));
        assertThat(auditCount(poA1, "REWORK_HELD")).isEqualTo(1);
        assertThat(auditCount(poA1, "REWORK_RELEASED")).isEqualTo(1);
    }

    @Test
    void failCreatesTheGrnFlaggedQcFailedPublishesTheEventAndDoesNotBlock() throws Exception {
        logBl(poA1, "2026-09-01");
        logPackingList(poA1, "PL-A1", skuA1, 10);
        setInspectionDue(poA1, true, null);
        logInspection(poA1, "INS-1", "FAIL"); // shipped anyway / found post-shipment

        mockMvc.perform(createGrn(poA1))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.provenance").value("QC_FAILED"))
            .andExpect(jsonPath("$.qcFailed").value(true))
            .andExpect(jsonPath("$.exists").value(true)); // the GRN exists — the match is NOT blocked

        // Both the receipted event (6.5's trigger) and the QC-failure seam fired.
        assertThat(applicationEvents.stream(ProvisionalGoodsReceiptEvent.class).count()).isEqualTo(1);
        assertThat(applicationEvents.stream(com.shvoy.payments.event.QcFailureEvent.class).toList())
            .singleElement().satisfies(e -> assertThat(e.purchaseOrderId()).isEqualTo(poA1));
    }

    @Test
    void aReworkHoldOnOneConsignmentDoesNotStopASibling() throws Exception {
        UUID shipmentId = logBl(poA1, "2026-09-01");
        logPackingList(poA1, "PL-A1", skuA1, 10);
        mockMvc.perform(post("/api/shipments/{s}/consignments", shipmentId)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purchaseOrderId\":\"" + poA2 + "\"}"))
            .andExpect(status().isCreated());
        logPackingList(poA2, "PL-A2", skuA2, 5);

        // A1 held in rework; A2 receipts independently.
        logInspection(poA1, "INS-1", "REWORK_REQUIRED");
        mockMvc.perform(createGrn(poA2)).andExpect(status().isCreated());
        mockMvc.perform(createGrn(poA1))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONSIGNMENT_IN_REWORK_HOLD"));
    }

    @Test
    void clearingInspectionDueRequiresAReason() throws Exception {
        logBl(poA1, "2026-09-01");
        setInspectionDue(poA1, true, null);
        mockMvc.perform(post("/api/purchase-orders/{po}/shipment/inspection-due", poA1)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"due\":false}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void reCreatingAnExistingGrnIsRejected() throws Exception {
        logBl(poA1, "2026-09-01");
        logPackingList(poA1, "PL-A1", skuA1, 10);
        mockMvc.perform(createGrn(poA1)).andExpect(status().isCreated());

        mockMvc.perform(createGrn(poA1))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PROVISIONAL_GRN_EXISTS"));
    }

    @Test
    void amendmentIsAuditedAndUpdatesQuantities() throws Exception {
        logBl(poA1, "2026-09-01");
        logPackingList(poA1, "PL-A1", skuA1, 10);
        mockMvc.perform(createGrn(poA1)).andExpect(status().isCreated());

        mockMvc.perform(put("/api/purchase-orders/{po}/shipment/provisional-grn", poA1)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"skuId\":\"" + skuA1 + "\",\"quantity\":8}],\"reason\":\"miscount corrected\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines[0].receivedQuantity").value(8));

        List<java.util.Map<String, Object>> amendments = jdbcTemplate.queryForList(
            "SELECT detail FROM shipment_document_audit_events "
                + "WHERE purchase_order_id = ? AND event_type = 'PROVISIONAL_GRN_AMENDED'", poA1);
        assertThat(amendments).hasSize(1);
        assertThat(amendments.get(0).get("detail").toString()).contains("miscount corrected");
    }

    @Test
    void aPackingListCorrectionAfterGrnDoesNotSilentlyAlterTheGrn() throws Exception {
        logBl(poA1, "2026-09-01");
        logPackingList(poA1, "PL-A1", skuA1, 10);
        mockMvc.perform(createGrn(poA1)).andExpect(status().isCreated());

        // Correct the packing list to a different quantity after the GRN was issued.
        logPackingList(poA1, "PL-A1", skuA1, 999);

        // The GRN is a snapshot — unchanged.
        mockMvc.perform(get("/api/purchase-orders/{po}/shipment/provisional-grn", poA1)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines[0].receivedQuantity").value(10));
    }

    @Test
    void coLoadedSiblingsReceiptIndependently() throws Exception {
        UUID shipmentId = logBl(poA1, "2026-09-01");
        logPackingList(poA1, "PL-A1", skuA1, 10);
        // Co-load A2 onto the same BL, with its own packing list.
        mockMvc.perform(post("/api/shipments/{s}/consignments", shipmentId)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purchaseOrderId\":\"" + poA2 + "\"}"))
            .andExpect(status().isCreated());
        logPackingList(poA2, "PL-A2", skuA2, 5);

        // Receipt only A1's portion.
        mockMvc.perform(createGrn(poA1)).andExpect(status().isCreated());

        mockMvc.perform(get("/api/purchase-orders/{po}/shipment/provisional-grn", poA1)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(jsonPath("$.exists").value(true));
        // A2 — a sibling on the same BL — is untouched.
        mockMvc.perform(get("/api/purchase-orders/{po}/shipment/provisional-grn", poA2)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(jsonPath("$.exists").value(false))
            .andExpect(jsonPath("$.receiptStatus").value("DOCUMENTS_PENDING"));
    }

    @Test
    void cannotCreateAgainstAnotherCompanysPurchaseOrder() throws Exception {
        mockMvc.perform(createGrn(poB))
            .andExpect(status().isNotFound());
    }

    // --- helpers ---

    private UUID logBl(UUID poId, String blDate) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/purchase-orders/{po}/shipment/bill-of-lading", poId)
                .file(file("bl.pdf")).param("blReference", "BL-001").param("blDate", blDate)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.shipmentId"));
    }

    private void logPackingList(UUID poId, String reference, UUID skuId, int quantity) throws Exception {
        String lines = "[{\"skuId\":\"" + skuId + "\",\"quantity\":" + quantity + "}]";
        mockMvc.perform(multipart("/api/purchase-orders/{po}/shipment/packing-list", poId)
                .file(file("pl.pdf")).param("reference", reference).param("date", "2026-08-20").param("lines", lines)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isCreated());
    }

    private MockHttpServletRequestBuilder createGrn(UUID poId) {
        return post("/api/purchase-orders/{po}/shipment/provisional-grn", poId)
            .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId);
    }

    private void logInspection(UUID poId, String reference, String outcome) throws Exception {
        mockMvc.perform(multipart("/api/purchase-orders/{po}/shipment/inspection-report", poId)
                .file(file("ins.pdf")).param("reference", reference).param("date", "2026-08-25").param("outcome", outcome)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isCreated());
    }

    private void setInspectionDue(UUID poId, boolean due, String reason) throws Exception {
        String body = reason == null ? "{\"due\":" + due + "}"
            : "{\"due\":" + due + ",\"reason\":\"" + reason + "\"}";
        mockMvc.perform(post("/api/purchase-orders/{po}/shipment/inspection-due", poId)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
    }

    private String consignmentStatus(UUID poId) {
        return jdbcTemplate.queryForObject(
            "SELECT receipt_status FROM shipment_consignments WHERE purchase_order_id = ?", String.class, poId);
    }

    private int auditCount(UUID poId, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM shipment_document_audit_events WHERE purchase_order_id = ? AND event_type = ?",
            Integer.class, poId, eventType);
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, "application/pdf", "bytes".getBytes(StandardCharsets.UTF_8));
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

    private UUID insertSku(UUID supplierId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, description, status, created_at, company_id) "
                + "VALUES (?, ?, ?, 'Widget', 'ACTIVE', ?, ?)",
            id, supplierId, "SKU-" + id, Timestamp.from(Instant.now()), companyA);
        return id;
    }
}
