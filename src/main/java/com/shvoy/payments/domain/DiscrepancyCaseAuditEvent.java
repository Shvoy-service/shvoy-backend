package com.shvoy.payments.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.shvoy.TenantScoped;

/**
 * One immutable entry on a discrepancy case's audit trail (Story 6.6) — the
 * case's paper trail. Same construct-only, no-delete-path shape as the other
 * audit trails in the codebase. {@code actor} is nullable: an auto-resolve on a
 * passing re-run is a system action with no user.
 */
@Entity
@Table(name = "discrepancy_case_audit_events")
public class DiscrepancyCaseAuditEvent extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private DiscrepancyCaseAuditEventType eventType;

    @Column(name = "detail", length = 2000)
    private String detail;

    @Column(name = "actor")
    private UUID actor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DiscrepancyCaseAuditEvent() {
    }

    public DiscrepancyCaseAuditEvent(UUID caseId, DiscrepancyCaseAuditEventType eventType, String detail, UUID actor) {
        this.caseId = caseId;
        this.eventType = eventType;
        this.detail = detail;
        this.actor = actor;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public DiscrepancyCaseAuditEventType getEventType() {
        return eventType;
    }

    public String getDetail() {
        return detail;
    }

    public UUID getActor() {
        return actor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
