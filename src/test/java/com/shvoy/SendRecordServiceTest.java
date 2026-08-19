package com.shvoy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 9.4 (9.6 folded in) — the send record: written append-only, tenant-scoped,
 * and <strong>never carrying the email body</strong>. Also confirms the profile
 * selection (test = console, so no SES/AWS in tests).
 */
@SpringBootTest
@ActiveProfiles("test")
class SendRecordServiceTest {

    @Autowired
    SendRecordService sendRecordService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EmailSender emailSender;

    @Autowired
    org.springframework.context.ApplicationContext context;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();

    @BeforeEach
    void seed() {
        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        jdbcTemplate.update("DELETE FROM send_records WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void theTestProfileUsesTheConsoleSenderNotSes() {
        // No SES/AWS in local/test — the seam is profile-selected.
        assertThat(emailSender).isInstanceOf(ConsoleEmailSender.class);
        assertThat(context.getBeanNamesForType(SesEmailSender.class)).isEmpty();
    }

    @Test
    void aRecordIsWrittenWithMetadataAndNoBodyColumnExists() {
        TenantContext.set(companyA);
        sendRecordService.record(EmailSource.INVITATION, "user@acme.example", "You've been invited",
            SendOutcome.SENT, "ses-1", null, "ref-1");

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT source, recipient, subject, outcome, ses_message_id, entity_reference, company_id "
                + "FROM send_records WHERE company_id = ?", companyA);
        assertThat(row).containsEntry("source", "INVITATION").containsEntry("recipient", "user@acme.example")
            .containsEntry("subject", "You've been invited").containsEntry("outcome", "SENT")
            .containsEntry("ses_message_id", "ses-1").containsEntry("entity_reference", "ref-1");

        // No body: the table has no column for it (token hygiene) — asserted structurally.
        assertThat(hasColumn("send_records", "body")).isFalse();
    }

    @Test
    void bothFailureClassesRecordTheirOutcomeAndError() {
        TenantContext.set(companyA);
        sendRecordService.record(EmailSource.PURCHASE_ORDER, "sup@x.example", "PO-1",
            SendOutcome.FAILED_PERMANENT, null, "Email address is not verified", "PO-1");
        sendRecordService.record(EmailSource.DISCREPANCY, "fin@x.example", "Blocked",
            SendOutcome.FAILED_TRANSIENT, null, "Maximum sending rate exceeded", "PO-2");

        assertThat(outcomeCount(companyA, "FAILED_PERMANENT")).isEqualTo(1);
        assertThat(outcomeCount(companyA, "FAILED_TRANSIENT")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT error FROM send_records WHERE company_id = ? AND outcome = 'FAILED_PERMANENT'", String.class, companyA))
            .contains("not verified");
    }

    @Test
    void recordsAreTenantScoped() {
        TenantContext.set(companyA);
        sendRecordService.record(EmailSource.INVITATION, "a@x.example", "A", SendOutcome.SENT, "m", null, null);
        TenantContext.set(companyB);
        sendRecordService.record(EmailSource.INVITATION, "b@x.example", "B", SendOutcome.SENT, "m", null, null);

        // Each company sees only its own row at the DB level.
        assertThat(rowCount(companyA)).isEqualTo(1);
        assertThat(rowCount(companyB)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT recipient FROM send_records WHERE company_id = ?", String.class, companyA)).isEqualTo("a@x.example");
    }

    private boolean hasColumn(String table, String column) {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns WHERE UPPER(table_name) = UPPER(?) AND UPPER(column_name) = UPPER(?)",
            Integer.class, table, column);
        return n != null && n > 0;
    }

    private int rowCount(UUID company) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM send_records WHERE company_id = ?", Integer.class, company);
    }

    private int outcomeCount(UUID company, String outcome) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM send_records WHERE company_id = ? AND outcome = ?", Integer.class, company, outcome);
    }
}
