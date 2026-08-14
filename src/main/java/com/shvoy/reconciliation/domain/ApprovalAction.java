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
 * One approver's decision on a routed PI — an approval or a rejection (Story
 * 5.5). <strong>Immutable</strong>: construct-only, no mutators, never updated
 * or deleted, matching Roadmap v2's audit standard (approvals and rejections
 * are permanent history). The same shape as the codebase's other audit
 * records — {@code PurchaseOrderSend} (4.7), {@code PurchaseOrderPriceOverride}
 * (4.5), {@code PriceFileUpload} (3.5).
 *
 * Records the four things the story requires: <em>who</em>
 * ({@code actorUserId}), <em>when</em> ({@code createdAt}), <em>on what
 * basis</em> ({@code reconciliationId} — the exact comparison the approver
 * acted on, so "the variance they saw" is reconstructable from that immutable
 * record), and their <em>comment/reason</em>.
 *
 * Attached to the PI ({@code proformaInvoiceId}) rather than the
 * reconciliation, since the PI is the unit of approval; the {@code
 * reconciliationId} is carried alongside for the audit basis. A price-increase
 * PI accumulates multiple {@code APPROVE} rows (the distinct pool sign-offs);
 * a single {@code REJECT} row is enough to reject.
 */
@Entity
@Table(name = "approval_actions")
public class ApprovalAction extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "proforma_invoice_id", nullable = false)
    private UUID proformaInvoiceId;

    @Column(name = "reconciliation_id", nullable = false)
    private UUID reconciliationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 20)
    private ApprovalActionType actionType;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    @Column(name = "comment", length = 2000)
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ApprovalAction() {
    }

    public ApprovalAction(UUID proformaInvoiceId, UUID reconciliationId, ApprovalActionType actionType,
            UUID actorUserId, String comment) {
        this.proformaInvoiceId = proformaInvoiceId;
        this.reconciliationId = reconciliationId;
        this.actionType = actionType;
        this.actorUserId = actorUserId;
        this.comment = comment;
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

    public ApprovalActionType getActionType() {
        return actionType;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getComment() {
        return comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
