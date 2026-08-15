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
 * One immutable entry on a credit's audit trail (Story 6.7) — who did what to
 * the credit, when, and why (the cancel reason; the applied invoice). Same
 * genuinely-append-only shape as the reconciliation (5.7) and payment (6.2)
 * audit trails: construct-only, and its repository exposes no update or delete
 * path, so there's no code path to alter or remove an entry.
 */
@Entity
@Table(name = "credit_ledger_audit_events")
public class CreditLedgerAuditEvent extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "credit_ledger_entry_id", nullable = false)
    private UUID creditLedgerEntryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private CreditLedgerAuditEventType eventType;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    @Column(name = "detail", length = 2000)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CreditLedgerAuditEvent() {
    }

    public CreditLedgerAuditEvent(UUID creditLedgerEntryId, CreditLedgerAuditEventType eventType,
            UUID actorUserId, String detail) {
        this.creditLedgerEntryId = creditLedgerEntryId;
        this.eventType = eventType;
        this.actorUserId = actorUserId;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCreditLedgerEntryId() {
        return creditLedgerEntryId;
    }

    public CreditLedgerAuditEventType getEventType() {
        return eventType;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
