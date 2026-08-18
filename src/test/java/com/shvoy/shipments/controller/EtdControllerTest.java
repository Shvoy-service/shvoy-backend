package com.shvoy.shipments.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Story 7.5 — ETD tracking. Covers the confirmed-vs-requested delta (positive /
 * negative / null), the first-touch create on an early ETD, the revision
 * history, the post-arrival rejection, and — the load-bearing assertion — that
 * ETD is <strong>provably inert</strong> beyond its own fields: no payment
 * effect, no anchor event.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "PURCHASING")
class EtdControllerTest {

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

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "etd-" + userAId + "@x.com", now, companyA);
        supplierA = insertSupplier(companyA);
        supplierB = insertSupplier(companyB);
    }

    @AfterEach
    void cleanUp() {
        for (UUID c : new UUID[] {companyA, companyB}) {
            jdbcTemplate.update("DELETE FROM shipment_etd_revisions WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM payment_grn_projection_lines WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM shipment_document_audit_events WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM shipment_consignments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM shipments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM payment_audit_events WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM payments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_order_audit_events WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", c);
        }
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    // --- first-touch, delta directions, null ---

    @Test
    void anEarlyEtdWithNoShipmentRecordYetCreatesItViaFirstTouch() throws Exception {
        UUID po = insertPo(companyA, supplierA, "2026-10-01"); // requested ETD
        assertThat(consignmentCount(po)).isZero(); // no shipment data at all yet

        setEtd(po, "2026-10-08", null) // supplier confirms a ready date weeks ahead of any document
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.confirmedEtd").value("2026-10-08"))
            .andExpect(jsonPath("$.requestedEtd").value("2026-10-01"))
            .andExpect(jsonPath("$.deltaDays").value(7)) // 7 days later than requested — the slip
            .andExpect(jsonPath("$.awaitingConfirmation").value(false));

        assertThat(consignmentCount(po)).isEqualTo(1); // the shipment/consignment now exists
    }

    @Test
    void anEarlierConfirmedEtdGivesANegativeDelta() throws Exception {
        UUID po = insertPo(companyA, supplierA, "2026-10-10");
        setEtd(po, "2026-10-04", null)
            .andExpect(jsonPath("$.deltaDays").value(-6)); // earlier than requested
    }

    @Test
    void noConfirmedEtdIsAwaitingConfirmationWithANullDelta() throws Exception {
        UUID po = insertPo(companyA, supplierA, "2026-10-10");
        // Seed a shipment record (via a consignment) but never a confirmed ETD.
        UUID shipment = insertShipment(companyA);
        consignment(shipment, po, companyA);

        mockMvc.perform(get("/api/purchase-orders/{po}/shipment/etd", po).header(TENANT, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.confirmedEtd").doesNotExist())
            .andExpect(jsonPath("$.deltaDays").doesNotExist())
            .andExpect(jsonPath("$.awaitingConfirmation").value(true));
    }

    @Test
    void aPoWithNoRequestedEtdStillRecordsTheConfirmedOneWithANullDelta() throws Exception {
        UUID po = insertPo(companyA, supplierA, null); // no requested ETD
        setEtd(po, "2026-10-08", null)
            .andExpect(jsonPath("$.confirmedEtd").value("2026-10-08"))
            .andExpect(jsonPath("$.requestedEtd").doesNotExist())
            .andExpect(jsonPath("$.deltaDays").doesNotExist());
    }

    // --- history ---

    @Test
    void revisingTwiceKeepsBothEntriesWithTheLatestCurrent() throws Exception {
        UUID po = insertPo(companyA, supplierA, "2026-10-01");
        setEtd(po, "2026-10-08", null).andExpect(status().isOk());
        setEtd(po, "2026-10-15", "vessel rolled").andExpect(status().isOk());

        mockMvc.perform(get("/api/purchase-orders/{po}/shipment/etd", po).header(TENANT, companyA.toString()))
            .andExpect(jsonPath("$.confirmedEtd").value("2026-10-15")) // current is the latest
            .andExpect(jsonPath("$.deltaDays").value(14))
            .andExpect(jsonPath("$.history.length()").value(2))
            .andExpect(jsonPath("$.history[0].confirmedEtd").value("2026-10-15")) // newest first
            .andExpect(jsonPath("$.history[0].reason").value("vessel rolled"))
            .andExpect(jsonPath("$.history[1].confirmedEtd").value("2026-10-08"))
            .andExpect(jsonPath("$.history[1].reason").doesNotExist()); // optional reason omitted first time
    }

    // --- the load-bearing rule: ETD is inert (no payment, no anchor) ---

    @Test
    void settingAnEtdHasNoPaymentOrAnchorSideEffect() throws Exception {
        UUID po = insertPo(companyA, supplierA, "2026-10-01");
        // A balance anchored to ARRIVAL with no due date — if ETD wrongly published an anchor, this would move.
        UUID balance = insertArrivalAnchoredBalance(po);

        setEtd(po, "2026-10-08", null).andExpect(status().isOk());

        // ETD is not an anchor: the payment is untouched — same status, still no due date.
        assertThat(paymentStatusOf(balance)).isEqualTo("PENDING");
        assertThat(dueDateOf(balance)).isNull();
    }

    // --- post-arrival rejection ---

    @Test
    void settingAnEtdAfterArrivalIsRejected() throws Exception {
        UUID po = insertPo(companyA, supplierA, "2026-10-01");
        UUID shipment = insertShipment(companyA);
        UUID consignmentId = consignment(shipment, po, companyA);
        jdbcTemplate.update("UPDATE shipment_consignments SET receipt_status = 'ARRIVED_CONFIRMED' WHERE id = ?", consignmentId);

        setEtd(po, "2026-10-08", null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ETD_NOT_SETTABLE_AFTER_ARRIVAL"));
    }

    // --- tenancy ---

    @Test
    void cannotSetEtdForAnotherCompanysPurchaseOrder() throws Exception {
        UUID po = insertPo(companyB, supplierB, "2026-10-01");

        mockMvc.perform(put("/api/purchase-orders/{po}/shipment/etd", po)
                .header(TENANT, companyA.toString()).header(USER, userAId.toString()) // acting as company A
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmedEtd\":\"2026-10-08\"}"))
            .andExpect(status().isNotFound());
    }

    // --- driving ---

    private ResultActions setEtd(UUID po, String date, String reason) throws Exception {
        String body = reason == null
            ? "{\"confirmedEtd\":\"" + date + "\"}"
            : "{\"confirmedEtd\":\"" + date + "\",\"reason\":\"" + reason + "\"}";
        return mockMvc.perform(put("/api/purchase-orders/{po}/shipment/etd", po)
            .header(TENANT, companyA.toString()).header(USER, userAId.toString())
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // --- seeding ---

    private UUID insertSupplier(UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            id, "Sup-" + id, Timestamp.from(Instant.now()), company);
        return id;
    }

    private UUID insertPo(UUID company, UUID supplier, String requestedEtd) {
        UUID po = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, requested_etd, created_by, created_at, generated_at, company_id) "
                + "VALUES (?, ?, ?, 'SENT', ?, ?, ?, ?, ?)",
            po, supplier, "PO-" + po, requestedEtd == null ? null : java.sql.Date.valueOf(requestedEtd),
            userAId, now, now, company);
        return po;
    }

    private UUID insertShipment(UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO shipments (id, bl_reference, created_at, company_id) VALUES (?, ?, ?, ?)",
            id, "BL-" + id, Timestamp.from(Instant.now()), company);
        return id;
    }

    private UUID consignment(UUID shipment, UUID po, UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO shipment_consignments (id, shipment_id, purchase_order_id, receipt_status, detached, inspection_due, created_at, company_id) "
                + "VALUES (?, ?, ?, 'DOCUMENTS_PENDING', FALSE, FALSE, ?, ?)",
            id, shipment, po, Timestamp.from(Instant.now()), company);
        return id;
    }

    private UUID insertArrivalAnchoredBalance(UUID po) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO payments (id, company_id, purchase_order_id, type, amount_amount, currency, status, created_at, anchor_event, days_offset) "
                + "VALUES (?, ?, ?, 'BALANCE', 20.00, 'USD', 'PENDING', ?, 'ARRIVAL', 30)",
            id, companyA, po, Timestamp.from(Instant.now()));
        return id;
    }

    // --- assertions ---

    private int consignmentCount(UUID po) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM shipment_consignments WHERE purchase_order_id = ?", Integer.class, po);
    }

    private String paymentStatusOf(UUID paymentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    private java.sql.Date dueDateOf(UUID paymentId) {
        return jdbcTemplate.queryForObject("SELECT due_date FROM payments WHERE id = ?", java.sql.Date.class, paymentId);
    }
}
