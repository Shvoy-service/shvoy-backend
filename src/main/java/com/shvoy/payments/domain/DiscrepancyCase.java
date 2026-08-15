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
 * A discrepancy case (Story 6.6) — one per payment the three-way match (6.5)
 * blocked, putting the mismatch in front of a named resolver. Claimable-queue
 * model: {@code claimedBy} is the "named resolver" the moment someone claims it.
 *
 * <p>Resolves one of four ways: the data is corrected and the match re-passes
 * ({@code CORRECTED}, auto), a credit is agreed ({@code CREDITED}, path b), the
 * difference is accepted as-is ({@code OVERRIDDEN}, path c), or the invoice is
 * contested ({@code DISPUTED}, path d — the payment stays blocked). Every
 * transition is audited separately; this entity just holds the current state.
 */
@Entity
@Table(name = "discrepancy_cases")
public class DiscrepancyCase extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DiscrepancyStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_type", length = 20)
    private DiscrepancyResolutionType resolutionType;

    @Column(name = "failure_detail", length = 2000)
    private String failureDetail;

    @Column(name = "credit_ledger_entry_id")
    private UUID creditLedgerEntryId;

    @Column(name = "claimed_by")
    private UUID claimedBy;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "resolution_reason", length = 2000)
    private String resolutionReason;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected DiscrepancyCase() {
    }

    public DiscrepancyCase(UUID paymentId, UUID purchaseOrderId, String failureDetail) {
        this.paymentId = paymentId;
        this.purchaseOrderId = purchaseOrderId;
        this.failureDetail = failureDetail;
        this.status = DiscrepancyStatus.OPEN;
        this.createdAt = Instant.now();
    }

    /** Refresh the mismatch detail when the match re-fails — the same case, not a new one. */
    public void updateDetail(String failureDetail) {
        this.failureDetail = failureDetail;
        this.updatedAt = Instant.now();
    }

    /** A resolver claims the case — their name goes on the record (the claimable-queue model). */
    public void claim(UUID resolver) {
        this.claimedBy = resolver;
        this.claimedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** Link the ledger credit logged from this case (path b); the case resolves later, when the match passes. */
    public void linkCredit(UUID creditLedgerEntryId) {
        this.creditLedgerEntryId = creditLedgerEntryId;
        this.updatedAt = Instant.now();
    }

    /** Resolve the case — {@code CORRECTED}/{@code CREDITED} on a passing match, {@code OVERRIDDEN} on a force-pass. */
    public void resolve(DiscrepancyResolutionType type, UUID resolver, String reason) {
        this.status = DiscrepancyStatus.RESOLVED;
        this.resolutionType = type;
        this.resolvedBy = resolver;
        this.resolutionReason = reason;
        this.resolvedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** Contest the invoice outright (path d) — the payment stays BLOCKED. */
    public void dispute(UUID actor, String reason) {
        this.status = DiscrepancyStatus.DISPUTED;
        this.resolvedBy = actor;
        this.resolutionReason = reason;
        this.updatedAt = Instant.now();
    }

    /** Active = not yet resolved (a re-fail updates this case; a fail with no active case opens a new one). */
    public boolean isActive() {
        return status == DiscrepancyStatus.OPEN || status == DiscrepancyStatus.DISPUTED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public DiscrepancyStatus getStatus() {
        return status;
    }

    public DiscrepancyResolutionType getResolutionType() {
        return resolutionType;
    }

    public String getFailureDetail() {
        return failureDetail;
    }

    public UUID getCreditLedgerEntryId() {
        return creditLedgerEntryId;
    }

    public UUID getClaimedBy() {
        return claimedBy;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public String getResolutionReason() {
        return resolutionReason;
    }

    public UUID getResolvedBy() {
        return resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
