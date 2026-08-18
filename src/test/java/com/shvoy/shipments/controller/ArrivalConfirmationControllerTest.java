package com.shvoy.shipments.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Story 7.6 — physical arrival confirmation. The governing principle under test:
 * <strong>arrival never unwinds settled state.</strong> A count mismatch (vs the
 * GRN, never the PO) raises a discrepancy record for the credit lane and moves
 * only the consignment's status — the payment, the match, and closure are
 * provably untouched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "PURCHASING")
class ArrivalConfirmationControllerTest {

    private static final String TENANT = "X-Debug-Company-Id";
    private static final String USER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID userAId;
    UUID supplierA;
    UUID supplierB;
    UUID skuA;
    UUID skuA2;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "arrival-" + userAId + "@x.com", now, companyA);
        supplierA = insertSupplier(companyA);
        supplierB = insertSupplier(companyB);
        skuA = insertSku(supplierA, companyA);
        skuA2 = insertSku(supplierA, companyA);
    }

    @AfterEach
    void cleanUp() {
        for (UUID c : new UUID[] {companyA, companyB}) {
            jdbcTemplate.update("DELETE FROM arrival_discrepancy_lines WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM arrival_discrepancies WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM payment_grn_projection_lines WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM shipment_goods_receipt_lines WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM shipment_document_audit_events WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM shipment_consignments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM shipments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM payment_audit_events WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM payments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM skus WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", c);
        }
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    // --- the comparison: vs the GRN, both outcomes ---

    @Test
    void aCleanArrivalConfirmsAgainstTheGrn() throws Exception {
        UUID po = provisionallyReceiptedPo(companyA, "INSPECTION_NOT_DUE");
        grnLine(po, skuA, 10);

        confirm(po, "2026-09-10", skuA, 10)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.receiptStatus").value("ARRIVED_CONFIRMED"))
            .andExpect(jsonPath("$.arrivalDate").value("2026-09-10"))
            .andExpect(jsonPath("$.arrivalDiscrepancyId").doesNotExist())
            .andExpect(jsonPath("$.discrepancyLines.length()").value(0));

        assertThat(auditCount(po, "ARRIVAL_CONFIRMED")).isEqualTo(1);
    }

    @Test
    void aShortArrivalRaisesADiscrepancyWithoutTouchingThePaymentOrClosure() throws Exception {
        UUID po = provisionallyReceiptedPo(companyA, "INSPECTION_NOT_DUE");
        grnLine(po, skuA, 10);
        UUID paidBalance = insertPaidBalance(po); // a payment already settled — must not move
        jdbcTemplate.update("UPDATE purchase_orders SET status = 'CLOSED' WHERE id = ?", po); // closed on receipt

        confirm(po, "2026-09-10", skuA, 8) // only 8 of 10 turned up at the door
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.receiptStatus").value("ARRIVED_WITH_DISCREPANCY"))
            .andExpect(jsonPath("$.discrepancyLines[0].skuId").value(skuA.toString()))
            .andExpect(jsonPath("$.discrepancyLines[0].expectedQuantity").value(10))
            .andExpect(jsonPath("$.discrepancyLines[0].arrivedQuantity").value(8))
            .andExpect(jsonPath("$.discrepancyLines[0].direction").value("SHORT"));

        // The core rule: settled state is untouched — the paid payment stays PAID, the closed PO stays CLOSED.
        assertThat(statusOfPayment(paidBalance)).isEqualTo("PAID");
        assertThat(statusOfPo(po)).isEqualTo("CLOSED");
        // ...and no discrepancy CASE was opened (arrival is not a match discrepancy) and no match ran.
        assertThat(discrepancyCaseCount(po)).isZero();
    }

    @Test
    void anOverArrivalRecordsTheOverDirection() throws Exception {
        UUID po = provisionallyReceiptedPo(companyA, "INSPECTION_NOT_DUE");
        grnLine(po, skuA, 10);

        confirm(po, "2026-09-10", skuA, 13)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.receiptStatus").value("ARRIVED_WITH_DISCREPANCY"))
            .andExpect(jsonPath("$.discrepancyLines[0].direction").value("OVER"))
            .andExpect(jsonPath("$.discrepancyLines[0].arrivedQuantity").value(13));
    }

    // --- qc_failed: quantity and quality discrepancies coexist as separate records ---

    @Test
    void anArrivalOnAQcFailedConsignmentConfirmsNormallyAndAddsAQuantityRecordAlongsideTheQuality() throws Exception {
        UUID po = provisionallyReceiptedPo(companyA, "QC_FAILED"); // the quality discrepancy already exists (7.4)
        grnLine(po, skuA, 10);

        confirm(po, "2026-09-10", skuA, 7)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.receiptStatus").value("ARRIVED_WITH_DISCREPANCY"))
            .andExpect(jsonPath("$.discrepancyLines[0].direction").value("SHORT"));

        // A distinct arrival (quantity) discrepancy record now exists — not merged with the quality one.
        assertThat(arrivalDiscrepancyCount(po)).isEqualTo(1);
    }

    // --- the ARRIVAL anchor → due date, end to end ---

    @Test
    void confirmingArrivalPublishesTheAnchorAndCalculatesAnArrivalAnchoredDueDate() throws Exception {
        UUID po = provisionallyReceiptedPo(companyA, "INSPECTION_NOT_DUE");
        grnLine(po, skuA, 10);
        UUID balance = insertArrivalAnchoredBalance(po, 30); // due = arrival date + 30

        confirm(po, "2026-09-10", skuA, 10).andExpect(status().isCreated());

        assertThat(dueDateOf(balance)).isEqualTo(LocalDate.of(2026, 10, 10));
    }

    @Test
    void correctingTheArrivalDateRepublishesAndRecalculatesTheDueDate() throws Exception {
        UUID po = provisionallyReceiptedPo(companyA, "INSPECTION_NOT_DUE");
        grnLine(po, skuA, 10);
        UUID balance = insertArrivalAnchoredBalance(po, 30);

        confirm(po, "2026-09-10", skuA, 10).andExpect(status().isCreated());
        assertThat(dueDateOf(balance)).isEqualTo(LocalDate.of(2026, 10, 10));

        mockMvc.perform(put("/api/purchase-orders/{po}/shipment/arrival", po)
                .header(TENANT, companyA.toString()).header(USER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"arrivalDate\":\"2026-09-20\"}"))
            .andExpect(status().isOk());

        assertThat(dueDateOf(balance)).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(auditCount(po, "ARRIVAL_DATE_CORRECTED")).isEqualTo(1);
    }

    // --- per-consignment independence on a co-load ---

    @Test
    void siblingConsignmentsOnAcoLoadArriveIndependently() throws Exception {
        UUID shipment = insertShipment(companyA);
        UUID po1 = insertPo(companyA, supplierA);
        UUID po2 = insertPo(companyA, supplierA);
        consignment(shipment, po1, companyA, "PROVISIONALLY_RECEIPTED", "INSPECTION_NOT_DUE");
        consignment(shipment, po2, companyA, "PROVISIONALLY_RECEIPTED", "INSPECTION_NOT_DUE");
        grnLine(po1, skuA, 10);
        grnLine(po2, skuA, 10);

        confirm(po1, "2026-09-10", skuA, 10).andExpect(status().isCreated());

        // po1 arrived; po2's own portion is untouched — one PO's goods can be counted-in while a sibling's wait.
        assertThat(receiptStatusOf(po1)).isEqualTo("ARRIVED_CONFIRMED");
        assertThat(receiptStatusOf(po2)).isEqualTo("PROVISIONALLY_RECEIPTED");
    }

    // --- preconditions ---

    @Test
    void confirmingArrivalBeforeAGrnExistsIsRejected() throws Exception {
        UUID po = insertPo(companyA, supplierA);
        UUID shipment = insertShipment(companyA);
        consignment(shipment, po, companyA, "DOCUMENTS_PENDING", null); // no GRN yet

        confirm(po, "2026-09-10", skuA, 10)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONSIGNMENT_NOT_ARRIVAL_ELIGIBLE"));
    }

    @Test
    void confirmingArrivalTwiceIsRejected() throws Exception {
        UUID po = provisionallyReceiptedPo(companyA, "INSPECTION_NOT_DUE");
        grnLine(po, skuA, 10);
        confirm(po, "2026-09-10", skuA, 10).andExpect(status().isCreated());

        confirm(po, "2026-09-11", skuA, 10)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ARRIVAL_ALREADY_CONFIRMED"));
    }

    @Test
    void aGrnAmendmentAfterArrivalIsRejected() throws Exception {
        UUID po = provisionallyReceiptedPo(companyA, "INSPECTION_NOT_DUE");
        grnLine(po, skuA, 10);
        confirm(po, "2026-09-10", skuA, 10).andExpect(status().isCreated());

        // The GRN is settled history post-arrival — corrections flow to the discrepancy/credit lane, not an amend.
        mockMvc.perform(put("/api/purchase-orders/{po}/shipment/provisional-grn", po)
                .header(TENANT, companyA.toString()).header(USER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"skuId\":\"" + skuA + "\",\"quantity\":9}],\"reason\":\"late correction\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PROVISIONAL_GRN_NOT_AMENDABLE"));
    }

    @Test
    void cannotConfirmArrivalForAnotherCompanysPurchaseOrder() throws Exception {
        UUID po = provisionallyReceiptedPo(companyB, "INSPECTION_NOT_DUE");
        // No GRN line needed — the tenant guard rejects (404) before any GRN comparison is read.

        mockMvc.perform(post("/api/purchase-orders/{po}/shipment/arrival", po)
                .header(TENANT, companyA.toString()).header(USER, userAId.toString()) // acting as company A
                .contentType(MediaType.APPLICATION_JSON)
                .content(arrivalBody("2026-09-10", skuA, 10)))
            .andExpect(status().isNotFound());
    }

    // --- driving ---

    private ResultActions confirm(UUID po, String date, UUID sku, int qty) throws Exception {
        return mockMvc.perform(post("/api/purchase-orders/{po}/shipment/arrival", po)
            .header(TENANT, companyA.toString()).header(USER, userAId.toString())
            .contentType(MediaType.APPLICATION_JSON).content(arrivalBody(date, sku, qty)));
    }

    private String arrivalBody(String date, UUID sku, int qty) {
        return "{\"arrivalDate\":\"" + date + "\",\"arrivedLines\":[{\"skuId\":\"" + sku + "\",\"quantity\":" + qty + "}]}";
    }

    // --- seeding ---

    private UUID provisionallyReceiptedPo(UUID company, String provenance) {
        UUID po = insertPo(company, company.equals(companyA) ? supplierA : supplierB);
        UUID shipment = insertShipment(company);
        consignment(shipment, po, company, "PROVISIONALLY_RECEIPTED", provenance);
        return po;
    }

    private UUID insertSupplier(UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            id, "Sup-" + id, Timestamp.from(Instant.now()), company);
        return id;
    }

    private UUID insertSku(UUID supplier, UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, description, status, created_at, company_id) "
                + "VALUES (?, ?, ?, 'Widget', 'ACTIVE', ?, ?)",
            id, supplier, "SKU-" + id, Timestamp.from(Instant.now()), company);
        return id;
    }

    private UUID insertPo(UUID company, UUID supplier) {
        UUID po = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, generated_at, company_id) "
                + "VALUES (?, ?, ?, 'SENT', ?, ?, ?, ?)",
            po, supplier, "PO-" + po, userAId, now, now, company);
        return po;
    }

    private UUID insertShipment(UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO shipments (id, bl_reference, bl_date, created_at, company_id) VALUES (?, ?, ?, ?, ?)",
            id, "BL-" + id, java.sql.Date.valueOf("2026-09-01"), Timestamp.from(Instant.now()), company);
        return id;
    }

    private void consignment(UUID shipment, UUID po, UUID company, String receiptStatus, String provenance) {
        jdbcTemplate.update(
            "INSERT INTO shipment_consignments (id, shipment_id, purchase_order_id, receipt_status, grn_provenance, "
                + "detached, inspection_due, provisionally_receipted_by, provisionally_receipted_at, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, FALSE, FALSE, ?, ?, ?, ?)",
            UUID.randomUUID(), shipment, po, receiptStatus, provenance,
            "PROVISIONALLY_RECEIPTED".equals(receiptStatus) ? userAId : null,
            "PROVISIONALLY_RECEIPTED".equals(receiptStatus) ? Timestamp.from(Instant.now()) : null,
            Timestamp.from(Instant.now()), company);
    }

    private void grnLine(UUID po, UUID sku, int qty) {
        UUID consignmentId = jdbcTemplate.queryForObject(
            "SELECT id FROM shipment_consignments WHERE purchase_order_id = ? AND detached = FALSE", UUID.class, po);
        jdbcTemplate.update(
            "INSERT INTO shipment_goods_receipt_lines (id, company_id, consignment_id, sku_id, received_quantity, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), companyOf(po), consignmentId, sku, qty, Timestamp.from(Instant.now()));
    }

    private UUID insertPaidBalance(UUID po) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO payments (id, company_id, purchase_order_id, type, amount_amount, currency, status, created_at) "
                + "VALUES (?, ?, ?, 'BALANCE', 20.00, 'USD', 'PAID', ?)",
            id, companyOf(po), po, Timestamp.from(Instant.now()));
        return id;
    }

    private UUID insertArrivalAnchoredBalance(UUID po, int daysOffset) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO payments (id, company_id, purchase_order_id, type, amount_amount, currency, status, created_at, anchor_event, days_offset) "
                + "VALUES (?, ?, ?, 'BALANCE', 20.00, 'USD', 'PENDING', ?, 'ARRIVAL', ?)",
            id, companyOf(po), po, Timestamp.from(Instant.now()), daysOffset);
        return id;
    }

    private UUID companyOf(UUID po) {
        return jdbcTemplate.queryForObject("SELECT company_id FROM purchase_orders WHERE id = ?", UUID.class, po);
    }

    // --- assertions ---

    private String receiptStatusOf(UUID po) {
        return jdbcTemplate.queryForObject(
            "SELECT receipt_status FROM shipment_consignments WHERE purchase_order_id = ? AND detached = FALSE",
            String.class, po);
    }

    private String statusOfPayment(UUID paymentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    private String statusOfPo(UUID po) {
        return jdbcTemplate.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class, po);
    }

    private LocalDate dueDateOf(UUID paymentId) {
        java.sql.Date d = jdbcTemplate.queryForObject(
            "SELECT due_date FROM payments WHERE id = ?", java.sql.Date.class, paymentId);
        return d == null ? null : d.toLocalDate();
    }

    private int discrepancyCaseCount(UUID po) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM discrepancy_cases WHERE purchase_order_id = ?", Integer.class, po);
    }

    private int arrivalDiscrepancyCount(UUID po) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM arrival_discrepancies WHERE purchase_order_id = ?", Integer.class, po);
    }

    private int auditCount(UUID po, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM shipment_document_audit_events WHERE purchase_order_id = ? AND event_type = ?",
            Integer.class, po, eventType);
    }
}
