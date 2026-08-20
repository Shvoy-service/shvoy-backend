package com.shvoy.containerfill.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.shvoy.TenantContext;

/**
 * The container-fill time-poll — the system's only time-triggered work. It runs
 * <strong>two checks, one scheduler</strong>: the approaching-deadline reminder
 * (Story 8.2) and the overdue-offer lapse (Story 8.3). Restart-safe by construction:
 * all state is in the DB ({@code reminder_sent_at}, the status transition), nothing
 * in-memory, so an auto-deploy mid-poll loses nothing.
 *
 * <p><strong>Cross-tenant discovery.</strong> A scheduled thread has no tenant
 * context, and Hibernate's {@code @TenantId} resolver throws without one — so each
 * discovery query is raw JDBC (it bypasses the tenant filter and returns every
 * company's due rows). Then, per offer, it sets {@link TenantContext} to that
 * offer's company and clears it in a {@code finally}, exactly as the request filter
 * does — so all the scoped work (load, send, stamp/transition, audit) runs in the
 * right tenant. Each per-offer unit is its own short transaction (one connection).
 */
@Component
public class ContainerFillReminderPoll {

    private static final Logger log = LoggerFactory.getLogger(ContainerFillReminderPoll.class);

    /** One reminder, one day out — a constant (configurable lead time deferred, the tolerance lesson). */
    static final Duration REMINDER_LEAD = Duration.ofHours(24);

    private final JdbcTemplate jdbcTemplate;
    private final ContainerFillReminderService reminderService;
    private final ContainerFillLapseService lapseService;

    ContainerFillReminderPoll(JdbcTemplate jdbcTemplate, ContainerFillReminderService reminderService,
            ContainerFillLapseService lapseService) {
        this.jdbcTemplate = jdbcTemplate;
        this.reminderService = reminderService;
        this.lapseService = lapseService;
    }

    /** One poll pass: send due reminders, then lapse overdue undecided offers. */
    public void runOnce() {
        sendDueReminders();
        lapseOverdueOffers();
    }

    /** Reminder due iff the offer is AWAITING_DECISION, unreminded, and within the lead window (deadline &lt;= now+24h). */
    private void sendDueReminders() {
        Timestamp threshold = Timestamp.from(Instant.now().plus(REMINDER_LEAD));
        List<Map<String, Object>> due = jdbcTemplate.queryForList(
            "SELECT id, company_id FROM container_fill_offers "
                + "WHERE status = 'AWAITING_DECISION' AND reminder_sent_at IS NULL AND deadline <= ?",
            threshold);
        forEachTenantScoped(due, "reminder", reminderService::sendReminder);
    }

    /** Lapse iff the offer is still AWAITING_DECISION and its deadline has passed — strictly {@code now > deadline}. */
    private void lapseOverdueOffers() {
        Timestamp now = Timestamp.from(Instant.now());
        List<Map<String, Object>> overdue = jdbcTemplate.queryForList(
            "SELECT id, company_id FROM container_fill_offers "
                + "WHERE status = 'AWAITING_DECISION' AND deadline < ?",
            now);
        forEachTenantScoped(overdue, "lapse", lapseService::lapse);
    }

    private void forEachTenantScoped(List<Map<String, Object>> rows, String action, java.util.function.Consumer<UUID> work) {
        for (Map<String, Object> row : rows) {
            UUID offerId = (UUID) row.get("id");
            UUID companyId = (UUID) row.get("company_id");
            TenantContext.set(companyId);
            try {
                work.accept(offerId);
            } catch (RuntimeException e) {
                // One tenant's failure must never halt the loop; the un-advanced state means the next poll retries.
                log.warn("Container-fill {} failed for offer {} (company {})", action, offerId, companyId, e);
            } finally {
                TenantContext.clear();
            }
        }
    }
}
