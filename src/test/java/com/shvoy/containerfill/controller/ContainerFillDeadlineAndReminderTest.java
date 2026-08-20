package com.shvoy.containerfill.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
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
 * Story 8.2 — the decision deadline (set/revise/reject) and the reminder poll
 * (send-once idempotence, lead window, re-arming, cross-tenant isolation). The
 * poll is driven directly via {@link ContainerFillReminderPoll#runOnce()} — the
 * {@code @Scheduled} trigger is {@code @Profile("!test")} so nothing auto-fires.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class ContainerFillDeadlineAndReminderTest {

    private static final String TENANT = "X-Debug-Company-Id";
    private static final String USER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ContainerFillReminderPoll reminderPoll;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID userAId;
    UUID userBId;
    String emailA;
    String emailB;
    UUID supplierA;
    UUID supplierB;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        userAId = UUID.randomUUID();
        userBId = UUID.randomUUID();
        emailA = "admin-a-" + userAId + "@x.com";
        emailB = "admin-b-" + userBId + "@x.com";
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, emailA, now, companyA);
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userBId, emailB, now, companyB);
        supplierA = insertSupplier(companyA);
        supplierB = insertSupplier(companyB);
    }

    @AfterEach
    void cleanUp() {
        for (UUID c : new UUID[] {companyA, companyB}) {
            jdbcTemplate.update("DELETE FROM container_fill_offer_audit_events WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM container_fill_offers WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM shipment_consignments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM shipments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", c);
        }
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    // --- setting the deadline ---

    @Test
    void settingADeadlineOnAnOpenOfferMovesItToAwaitingDecision() throws Exception {
        UUID offerId = flag(companyA, userAId, insertPo(companyA, supplierA), supplierA, "2.50");

        setDeadline(companyA, userAId, offerId, Instant.now().plus(Duration.ofDays(3)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("AWAITING_DECISION"))
            .andExpect(jsonPath("$.deadline").exists());
        assertThat(auditCount(offerId, "DEADLINE_SET")).isEqualTo(1);
    }

    @Test
    void revisingADeadlineWhileAwaitingIsAuditedSeparately() throws Exception {
        UUID offerId = flag(companyA, userAId, insertPo(companyA, supplierA), supplierA, "2.50");
        setDeadline(companyA, userAId, offerId, Instant.now().plus(Duration.ofDays(3))).andExpect(status().isOk());

        setDeadline(companyA, userAId, offerId, Instant.now().plus(Duration.ofDays(5))).andExpect(status().isOk());

        assertThat(auditCount(offerId, "DEADLINE_SET")).isEqualTo(1);
        assertThat(auditCount(offerId, "DEADLINE_REVISED")).isEqualTo(1);
    }

    @Test
    void aPastDeadlineIsRejected() throws Exception {
        UUID offerId = flag(companyA, userAId, insertPo(companyA, supplierA), supplierA, "2.50");
        setDeadline(companyA, userAId, offerId, Instant.now().minusSeconds(3600))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONTAINER_FILL_DEADLINE_IN_PAST"));
    }

    @Test
    void aDeadlineCannotBeSetOnACancelledOffer() throws Exception {
        UUID offerId = flag(companyA, userAId, insertPo(companyA, supplierA), supplierA, "2.50");
        mockMvc.perform(post("/api/container-fill-offers/{id}/cancel", offerId)
                .header(TENANT, companyA.toString()).header(USER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"withdrawn\"}"))
            .andExpect(status().isOk());

        setDeadline(companyA, userAId, offerId, Instant.now().plus(Duration.ofDays(3)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONTAINER_FILL_DEADLINE_NOT_SETTABLE"));
    }

    @Test
    void anotherCompanyCannotSetTheDeadline() throws Exception {
        UUID offerId = flag(companyA, userAId, insertPo(companyA, supplierA), supplierA, "2.50");
        setDeadline(companyB, userBId, offerId, Instant.now().plus(Duration.ofDays(3)))
            .andExpect(status().isNotFound());
    }

    // --- the reminder poll ---

    @Test
    void thePollSendsOneReminderWithinTheLeadWindowThenIsIdempotent() throws Exception {
        UUID offerId = flag(companyA, userAId, insertPo(companyA, supplierA), supplierA, "2.50");
        setDeadline(companyA, userAId, offerId, Instant.now().plusSeconds(3600)).andExpect(status().isOk());

        try (LogCapture logs = new LogCapture(ConsoleEmailSender.class)) {
            reminderPoll.runOnce();
            assertThat(logs.countMessagesContaining(emailA)).isEqualTo(1);
        }
        assertThat(auditCount(offerId, "REMINDER_SENT")).isEqualTo(1);
        assertThat(reminderSentAt(offerId)).isNotNull();

        try (LogCapture logs = new LogCapture(ConsoleEmailSender.class)) {
            reminderPoll.runOnce(); // second pass — the stamp excludes it
            assertThat(logs.countMessagesContaining(emailA)).isZero();
        }
        assertThat(auditCount(offerId, "REMINDER_SENT")).isEqualTo(1);
    }

    @Test
    void aDeadlineBeyondTheLeadWindowIsNotYetDue() throws Exception {
        UUID offerId = flag(companyA, userAId, insertPo(companyA, supplierA), supplierA, "2.50");
        setDeadline(companyA, userAId, offerId, Instant.now().plus(Duration.ofHours(48))).andExpect(status().isOk());

        reminderPoll.runOnce();

        assertThat(auditCount(offerId, "REMINDER_SENT")).isZero();
        assertThat(reminderSentAt(offerId)).isNull();
    }

    @Test
    void revisingTheDeadlineReArmsTheReminder() throws Exception {
        UUID offerId = flag(companyA, userAId, insertPo(companyA, supplierA), supplierA, "2.50");
        setDeadline(companyA, userAId, offerId, Instant.now().plusSeconds(3600)).andExpect(status().isOk());
        reminderPoll.runOnce();
        assertThat(reminderSentAt(offerId)).isNotNull();

        setDeadline(companyA, userAId, offerId, Instant.now().plus(Duration.ofHours(2))).andExpect(status().isOk());
        assertThat(reminderSentAt(offerId)).isNull(); // re-armed

        reminderPoll.runOnce();
        assertThat(auditCount(offerId, "REMINDER_SENT")).isEqualTo(2);
    }

    @Test
    void aDecidedOfferInTheWindowIsSkipped() throws Exception {
        UUID offerId = flag(companyA, userAId, insertPo(companyA, supplierA), supplierA, "2.50");
        setDeadline(companyA, userAId, offerId, Instant.now().plusSeconds(3600)).andExpect(status().isOk());
        // 8.3 isn't built yet — simulate a decision directly.
        jdbcTemplate.update("UPDATE container_fill_offers SET status = 'CONFIRMED' WHERE id = ?", offerId);

        reminderPoll.runOnce();

        assertThat(auditCount(offerId, "REMINDER_SENT")).isZero();
    }

    @Test
    void theReminderPollIsTenantIsolated() throws Exception {
        UUID offerA = flag(companyA, userAId, insertPo(companyA, supplierA), supplierA, "2.50");
        setDeadline(companyA, userAId, offerA, Instant.now().plusSeconds(3600)).andExpect(status().isOk());
        UUID offerB = flag(companyB, userBId, insertPo(companyB, supplierB), supplierB, "3.00");
        setDeadline(companyB, userBId, offerB, Instant.now().plusSeconds(3600)).andExpect(status().isOk());

        try (LogCapture logs = new LogCapture(ConsoleEmailSender.class)) {
            reminderPoll.runOnce();
            // Each company's reminder reaches only its own PURCHASING/ADMIN user.
            assertThat(logs.countMessagesContaining(emailA)).isEqualTo(1);
            assertThat(logs.countMessagesContaining(emailB)).isEqualTo(1);
        }
        assertThat(auditCount(offerA, "REMINDER_SENT")).isEqualTo(1);
        assertThat(auditCount(offerB, "REMINDER_SENT")).isEqualTo(1);
    }

    // --- driving ---

    private UUID flag(UUID company, UUID user, UUID po, UUID supplier, String cbm) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/purchase-orders/{po}/container-fill-offers", po)
                .header(TENANT, company.toString()).header(USER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"spareCbm\":" + cbm + ",\"supplierId\":\"" + supplier + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.offerId"));
    }

    private ResultActions setDeadline(UUID company, UUID user, UUID offerId, Instant deadline) throws Exception {
        return mockMvc.perform(put("/api/container-fill-offers/{id}/deadline", offerId)
            .header(TENANT, company.toString()).header(USER, user.toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"deadline\":\"" + deadline + "\"}"));
    }

    // --- seeding ---

    private UUID insertSupplier(UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            id, "Sup-" + id, Timestamp.from(Instant.now()), company);
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

    // --- assertions ---

    private int auditCount(UUID offerId, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM container_fill_offer_audit_events WHERE offer_id = ? AND event_type = ?",
            Integer.class, offerId, eventType);
    }

    private Timestamp reminderSentAt(UUID offerId) {
        return jdbcTemplate.queryForObject(
            "SELECT reminder_sent_at FROM container_fill_offers WHERE id = ?", Timestamp.class, offerId);
    }
}
