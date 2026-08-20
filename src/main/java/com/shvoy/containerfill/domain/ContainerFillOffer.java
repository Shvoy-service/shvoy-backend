package com.shvoy.containerfill.domain;

import java.math.BigDecimal;
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
 * A container-fill offer (Story 8.1) — a supplier has flagged spare CBM on a
 * container, and the importer must later decide whether to fill it. Capacity is
 * container-level, so the offer attaches to a {@code shipmentId} (the BL-level
 * record from 7.1), with the flagging {@code supplierId} captured <em>explicitly</em>
 * (a co-loaded container has several suppliers). A SHVOY user records the offer,
 * relayed by the supplier — suppliers have no logins.
 *
 * <p>Lifecycle: 8.1 creates it {@code OPEN} and can {@code CANCELLED} it (corrections
 * are cancel-and-relog, never a silent CBM edit once a deadline clock may be running).
 * 8.2 sets the {@code deadline} → {@code AWAITING_DECISION}; 8.3 resolves it. This
 * entity holds the current state; every transition is audited separately
 * ({@link ContainerFillOfferAuditEvent}).
 */
@Entity
@Table(name = "container_fill_offers")
public class ContainerFillOffer extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shipment_id", nullable = false)
    private UUID shipmentId;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "spare_cbm", nullable = false, precision = 10, scale = 2)
    private BigDecimal spareCbm;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ContainerFillOfferStatus status;

    @Column(name = "deadline")
    private Instant deadline;

    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "flagged_by", nullable = false)
    private UUID flaggedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ContainerFillOffer() {
    }

    public ContainerFillOffer(UUID shipmentId, UUID supplierId, BigDecimal spareCbm, String notes, UUID flaggedBy) {
        this.shipmentId = shipmentId;
        this.supplierId = supplierId;
        this.spareCbm = spareCbm;
        this.notes = notes;
        this.flaggedBy = flaggedBy;
        this.status = ContainerFillOfferStatus.OPEN;
        this.createdAt = Instant.now();
    }

    /** Withdraw an undecided offer — the correction path (relog a fresh offer rather than editing this one). */
    public void cancel() {
        this.status = ContainerFillOfferStatus.CANCELLED;
        this.updatedAt = Instant.now();
    }

    /** Set the first decision deadline (Story 8.2): OPEN → AWAITING_DECISION; arms the reminder. */
    public void setDeadline(Instant deadline) {
        this.deadline = deadline;
        this.status = ContainerFillOfferStatus.AWAITING_DECISION;
        this.reminderSentAt = null;
        this.updatedAt = Instant.now();
    }

    /** Renegotiate the deadline while AWAITING_DECISION — clears the reminder stamp so it re-arms. */
    public void reviseDeadline(Instant deadline) {
        this.deadline = deadline;
        this.reminderSentAt = null;
        this.updatedAt = Instant.now();
    }

    /** Stamp that the approaching-deadline reminder has been sent — the poll's idempotence record (Story 8.2). */
    public void markReminderSent() {
        this.reminderSentAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** Undecided = still in the operational queue: flagged or awaiting a decision, not yet decided/cancelled. */
    public boolean isUndecided() {
        return status == ContainerFillOfferStatus.OPEN || status == ContainerFillOfferStatus.AWAITING_DECISION;
    }

    /** Only an undecided offer can be cancelled — a decided, lapsed, or already-cancelled one is fixed. */
    public boolean isCancellable() {
        return isUndecided();
    }

    public UUID getId() {
        return id;
    }

    public UUID getShipmentId() {
        return shipmentId;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public BigDecimal getSpareCbm() {
        return spareCbm;
    }

    public ContainerFillOfferStatus getStatus() {
        return status;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public Instant getReminderSentAt() {
        return reminderSentAt;
    }

    public String getNotes() {
        return notes;
    }

    public UUID getFlaggedBy() {
        return flaggedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
