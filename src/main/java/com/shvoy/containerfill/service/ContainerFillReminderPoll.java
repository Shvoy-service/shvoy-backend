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
 * The container-fill time-poll (Story 8.2) — the system's first time-triggered
 * work. It finds offers whose decision-deadline reminder is due and sends each.
 * Restart-safe by construction: all state is in the DB ({@code reminder_sent_at}),
 * nothing in-memory, so an auto-deploy mid-poll loses nothing. (8.3's lapse check
 * will ride this same poll — one scheduler, two checks.)
 *
 * <p><strong>Cross-tenant discovery.</strong> A scheduled thread has no tenant
 * context, and Hibernate's {@code @TenantId} resolver throws without one — so the
 * discovery query is raw JDBC (it bypasses the tenant filter and returns every
 * company's due rows). Then, per offer, it sets {@link TenantContext} to that
 * offer's company and clears it in a {@code finally}, exactly as the request filter
 * does — so all the scoped work (load, send, stamp, audit) runs in the right tenant.
 */
@Component
public class ContainerFillReminderPoll {

    private static final Logger log = LoggerFactory.getLogger(ContainerFillReminderPoll.class);

    /** One reminder, one day out — a constant (configurable lead time deferred, the tolerance lesson). */
    static final Duration REMINDER_LEAD = Duration.ofHours(24);

    private final JdbcTemplate jdbcTemplate;
    private final ContainerFillReminderService reminderService;

    ContainerFillReminderPoll(JdbcTemplate jdbcTemplate, ContainerFillReminderService reminderService) {
        this.jdbcTemplate = jdbcTemplate;
        this.reminderService = reminderService;
    }

    /** One poll pass: reminder due iff the offer is AWAITING_DECISION, unreminded, and within the lead window. */
    public void runOnce() {
        Timestamp threshold = Timestamp.from(Instant.now().plus(REMINDER_LEAD));
        List<Map<String, Object>> due = jdbcTemplate.queryForList(
            "SELECT id, company_id FROM container_fill_offers "
                + "WHERE status = 'AWAITING_DECISION' AND reminder_sent_at IS NULL AND deadline <= ?",
            threshold);

        for (Map<String, Object> row : due) {
            UUID offerId = (UUID) row.get("id");
            UUID companyId = (UUID) row.get("company_id");
            TenantContext.set(companyId);
            try {
                reminderService.sendReminder(offerId);
            } catch (RuntimeException e) {
                // One tenant's failure must never halt the loop; the still-null stamp means the next poll retries.
                log.warn("Container-fill reminder failed for offer {} (company {})", offerId, companyId, e);
            } finally {
                TenantContext.clear();
            }
        }
    }
}
