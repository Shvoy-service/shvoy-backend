package com.shvoy.containerfill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import com.shvoy.EmailMessage;
import com.shvoy.EmailSender;

/**
 * Story 8.2 — the reminder's one safe exception to the no-retry rule. A send that
 * throws inside the reminder transaction rolls back the {@code reminder_sent_at}
 * stamp, so the next poll retries naturally. {@code EmailSender} is mocked to throw
 * once, then succeed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class ContainerFillReminderFailureTest {

    private static final String TENANT = "X-Debug-Company-Id";
    private static final String USER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ContainerFillReminderPoll reminderPoll;

    @MockitoBean
    EmailSender emailSender;

    final UUID companyA = UUID.randomUUID();
    UUID userAId;
    UUID supplierA;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "admin-" + userAId + "@x.com", now, companyA);
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
    void aSendFailureLeavesTheStampNullAndTheNextPollRetries() throws Exception {
        UUID offerId = flagWithDeadline();

        doThrow(new RuntimeException("SES unavailable")).doNothing().when(emailSender).send(any(EmailMessage.class));

        reminderPoll.runOnce(); // send throws → transaction rolls back
        assertThat(reminderSentAt(offerId)).isNull();
        assertThat(auditCount(offerId, "REMINDER_SENT")).isZero();

        reminderPoll.runOnce(); // sender recovers → the retry lands
        assertThat(reminderSentAt(offerId)).isNotNull();
        assertThat(auditCount(offerId, "REMINDER_SENT")).isEqualTo(1);
    }

    private UUID flagWithDeadline() throws Exception {
        UUID po = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, generated_at, company_id) "
                + "VALUES (?, ?, ?, 'SENT', ?, ?, ?, ?)",
            po, supplierA, "PO-" + po, userAId, now, now, companyA);

        MvcResult result = mockMvc.perform(post("/api/purchase-orders/{po}/container-fill-offers", po)
                .header(TENANT, companyA.toString()).header(USER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"spareCbm\":2.50,\"supplierId\":\"" + supplierA + "\"}"))
            .andReturn();
        UUID offerId = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.offerId"));

        mockMvc.perform(put("/api/container-fill-offers/{id}/deadline", offerId)
            .header(TENANT, companyA.toString()).header(USER, userAId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"deadline\":\"" + Instant.now().plusSeconds(3600) + "\"}"));
        return offerId;
    }

    private Timestamp reminderSentAt(UUID offerId) {
        return jdbcTemplate.queryForObject(
            "SELECT reminder_sent_at FROM container_fill_offers WHERE id = ?", Timestamp.class, offerId);
    }

    private int auditCount(UUID offerId, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM container_fill_offer_audit_events WHERE offer_id = ? AND event_type = ?",
            Integer.class, offerId, eventType);
    }
}
