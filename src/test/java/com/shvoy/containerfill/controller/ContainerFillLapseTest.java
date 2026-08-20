package com.shvoy.containerfill.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import com.shvoy.ConsoleEmailSender;
import com.shvoy.LogCapture;
import com.shvoy.containerfill.service.ContainerFillReminderPoll;

/**
 * Story 8.3 — the poll's second check (lapse) and the decide-vs-lapse race. The poll
 * is driven directly via {@link ContainerFillReminderPoll#runOnce()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class ContainerFillLapseTest {

    private static final String TENANT = "X-Debug-Company-Id";
    private static final String USER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ContainerFillReminderPoll reminderPoll;

    final UUID companyA = UUID.randomUUID();
    UUID userAId;
    String emailA;
    UUID supplierA;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        userAId = UUID.randomUUID();
        emailA = "admin-" + userAId + "@x.com";
        jdbcTemplate.update("INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, emailA, now, companyA);
        supplierA = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierA, "Sup-" + supplierA, now, companyA);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM container_fill_offer_audit_events WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM container_fill_offers WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM shipment_consignments WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM shipments WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM users WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM companies WHERE id = ?", companyA);
    }

    @Test
    void thePollLapsesAnOverdueOfferOnceNotifiesAndIsIdempotent() throws Exception {
        UUID offerId = flag(insertPo());
        makeOverdue(offerId);

        try (LogCapture logs = new LogCapture(ConsoleEmailSender.class)) {
            reminderPoll.runOnce();
            assertThat(logs.countMessagesContaining(emailA)).isEqualTo(1); // the lapse notification (consumer six)
        }
        assertThat(statusOf(offerId)).isEqualTo("LAPSED");
        assertThat(auditCount(offerId, "LAPSED")).isEqualTo(1);
        assertThat(lapseActorIsNull(offerId)).isTrue(); // system action, no user

        reminderPoll.runOnce(); // second pass — already LAPSED, skipped
        assertThat(auditCount(offerId, "LAPSED")).isEqualTo(1);
    }

    @Test
    void anOfferWhoseDeadlineHasNotPassedIsNotLapsed() throws Exception {
        UUID offerId = flag(insertPo());
        setDeadline(offerId, Instant.now().plusSeconds(3600)).andExpect(status().isOk()); // future

        reminderPoll.runOnce();

        assertThat(statusOf(offerId)).isEqualTo("AWAITING_DECISION");
        assertThat(auditCount(offerId, "LAPSED")).isZero();
    }

    @Test
    void decidingBeforeThePollWinsTheRaceAndThePollSkips() throws Exception {
        UUID offerId = flag(insertPo());
        makeOverdue(offerId); // deadline already passed, but...
        confirm(offerId).andExpect(status().isOk()); // ...the human decides first

        reminderPoll.runOnce();

        assertThat(statusOf(offerId)).isEqualTo("CONFIRMED");
        assertThat(auditCount(offerId, "LAPSED")).isZero();
    }

    @Test
    void decidingAfterALapseGetsTheInvalidTransitionCode() throws Exception {
        UUID offerId = flag(insertPo());
        makeOverdue(offerId);
        reminderPoll.runOnce();
        assertThat(statusOf(offerId)).isEqualTo("LAPSED");

        confirm(offerId)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONTAINER_FILL_OFFER_NOT_DECIDABLE"));
    }

    @Test
    void shipWithoutTouchesNothingElse() throws Exception {
        UUID po = insertPo();
        UUID offerId = flag(po);
        UUID shipmentId = shipmentIdOf(offerId);
        makeOverdue(offerId);

        reminderPoll.runOnce(); // → LAPSED (ship without)

        // The container proceeds exactly as it was: its one consignment intact, the PO untouched.
        assertThat(activeConsignmentCount(shipmentId)).isEqualTo(1);
        assertThat(poStatusOf(po)).isEqualTo("SENT");
    }

    // --- driving ---

    private UUID flag(UUID po) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/purchase-orders/{po}/container-fill-offers", po)
                .header(TENANT, companyA.toString()).header(USER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"spareCbm\":2.50,\"supplierId\":\"" + supplierA + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.offerId"));
    }

    private ResultActions setDeadline(UUID offerId, Instant deadline) throws Exception {
        return mockMvc.perform(put("/api/container-fill-offers/{id}/deadline", offerId)
            .header(TENANT, companyA.toString()).header(USER, userAId.toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"deadline\":\"" + deadline + "\"}"));
    }

    private ResultActions confirm(UUID offerId) throws Exception {
        return mockMvc.perform(post("/api/container-fill-offers/{id}/confirm", offerId)
            .header(TENANT, companyA.toString()).header(USER, userAId.toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    /** AWAITING_DECISION with a past deadline and an already-sent reminder (so only the lapse pass acts). */
    private void makeOverdue(UUID offerId) throws Exception {
        setDeadline(offerId, Instant.now().plusSeconds(86400)).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE container_fill_offers SET deadline = ?, reminder_sent_at = ? WHERE id = ?",
            Timestamp.from(Instant.now().minusSeconds(3600)), Timestamp.from(Instant.now().minusSeconds(1800)), offerId);
    }

    private UUID insertPo() {
        UUID po = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, generated_at, company_id) "
                + "VALUES (?, ?, ?, 'SENT', ?, ?, ?, ?)",
            po, supplierA, "PO-" + po, userAId, now, now, companyA);
        return po;
    }

    // --- assertions ---

    private String statusOf(UUID offerId) {
        return jdbcTemplate.queryForObject("SELECT status FROM container_fill_offers WHERE id = ?", String.class, offerId);
    }

    private UUID shipmentIdOf(UUID offerId) {
        return jdbcTemplate.queryForObject("SELECT shipment_id FROM container_fill_offers WHERE id = ?", UUID.class, offerId);
    }

    private int auditCount(UUID offerId, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM container_fill_offer_audit_events WHERE offer_id = ? AND event_type = ?",
            Integer.class, offerId, eventType);
    }

    private boolean lapseActorIsNull(UUID offerId) {
        Integer nonNull = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM container_fill_offer_audit_events WHERE offer_id = ? AND event_type = 'LAPSED' AND actor IS NOT NULL",
            Integer.class, offerId);
        return nonNull == 0;
    }

    private int activeConsignmentCount(UUID shipmentId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM shipment_consignments WHERE shipment_id = ? AND detached = FALSE", Integer.class, shipmentId);
    }

    private String poStatusOf(UUID poId) {
        return jdbcTemplate.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class, poId);
    }
}
