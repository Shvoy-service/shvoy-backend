package com.shvoy.reconciliation.domain;

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
 * One immutable entry on a PI's reconciliation audit trail (Story 5.7) —
 * Roadmap v2's "every price change, quantity change, approval, and override is
 * logged with user, timestamp, and reason — immutable history."
 *
 * <p><strong>Genuinely append-only, enforced structurally, not by convention.</strong>
 * This entity is construct-only: no setters, no mutators, nothing that can
 * change a field once written. Its repository ({@code
 * ReconciliationAuditEventRepository}) deliberately exposes no update or delete
 * operation. So there is no application code path to alter or remove a
 * recorded event — the trail can only grow. That's what lets the roadmap's
 * compliance positioning lean on it as evidence of reasonable care, rather
 * than it being merely un-edited-by-habit.
 *
 * <p>Keyed by {@code proformaInvoiceId} (always present, including for {@code
 * PI_LOGGED} before any {@code Reconciliation} exists); {@code
 * reconciliationId} is filled in once the comparison exists. {@code
 * actorUserId} is null for system events (the auto-confirm/route the system
 * decided). {@code detail} is a human-readable summary — the variance and
 * tolerance in force, or an approver's reason — captured at the time so the
 * entry stays explicable even after settings later change.
 */
@Entity
@Table(name = "reconciliation_audit_events")
public class ReconciliationAuditEvent extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "proforma_invoice_id", nullable = false)
    private UUID proformaInvoiceId;

    @Column(name = "reconciliation_id")
    private UUID reconciliationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private ReconciliationAuditEventType eventType;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "detail", length = 2000)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReconciliationAuditEvent() {
    }

    public ReconciliationAuditEvent(UUID proformaInvoiceId, UUID reconciliationId,
            ReconciliationAuditEventType eventType, UUID actorUserId, String detail) {
        this.proformaInvoiceId = proformaInvoiceId;
        this.reconciliationId = reconciliationId;
        this.eventType = eventType;
        this.actorUserId = actorUserId;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getProformaInvoiceId() {
        return proformaInvoiceId;
    }

    public UUID getReconciliationId() {
        return reconciliationId;
    }

    public ReconciliationAuditEventType getEventType() {
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
