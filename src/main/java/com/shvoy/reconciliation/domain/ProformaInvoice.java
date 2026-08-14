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

import com.shvoy.ConflictException;
import com.shvoy.ErrorCode;
import com.shvoy.TenantScoped;

/**
 * A supplier's confirmed proforma invoice, logged against a {@code
 * PurchaseOrder} — see Story 5.1 (model) and 5.2 (the logging endpoint,
 * {@code ProformaInvoiceService}). Variance calculation (5.3), tolerance/
 * auto-confirm (5.4), approval routing (5.5/5.6), and the full status-
 * lifecycle/audit trail (5.7) still don't exist yet.
 *
 * {@code purchaseOrderId} is a plain {@code UUID}, not a JPA relationship —
 * matching this codebase's flat-column convention throughout (see
 * PurchaseOrder, Supplier, Sku).
 *
 * <strong>Cardinality:</strong> a PO can receive more than one PI over
 * time — a supplier re-issuing a corrected proforma is a real scenario, so
 * this is one-to-many rather than strictly one-per-PO. {@code active}
 * identifies the current one: exactly one PI per PO is active at a time
 * (superseded PIs are kept, never deleted, for audit) — see {@link
 * #supersede}, called by {@code ProformaInvoiceService} on the prior active
 * PI immediately before a correction is saved as the new active one, same
 * split as {@code SkuPrice}'s validity window (modelled in 3.4, actually
 * superseded by 3.5's upload logic).
 *
 * {@code currency} is stored explicitly rather than assumed to match the
 * PO's, precisely so a mismatch is representable and detectable — Feature 5
 * routes a cross-currency PI to approval rather than rejecting it outright
 * (see docs/CONTRACT.md's Feature 5 section); that comparison is 5.3+'s job,
 * not this story's.
 */
@Entity
@Table(name = "proforma_invoices")
public class ProformaInvoice extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "pi_reference", nullable = false, length = 100)
    private String piReference;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProformaInvoiceStatus status;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "logged_by", nullable = false)
    private UUID loggedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ProformaInvoice() {
    }

    public ProformaInvoice(UUID purchaseOrderId, String piReference, String currency, UUID loggedBy) {
        this.purchaseOrderId = purchaseOrderId;
        this.piReference = piReference;
        this.currency = currency;
        this.status = ProformaInvoiceStatus.LOGGED;
        this.active = true;
        this.loggedBy = loggedBy;
        this.createdAt = Instant.now();
    }

    /**
     * Story 5.2's actual supersession step — flips this PI inactive when a
     * correction is logged against the same PO. {@code ProformaInvoiceService}
     * is the only caller, same trust-the-caller convention as {@code
     * PurchaseOrder#cancel}; it's called on whichever PI was previously
     * active for the PO, immediately before the new one is saved as active.
     */
    public void supersede() {
        transitionTo(ProformaInvoiceStatus.SUPERSEDED);
        this.active = false;
    }

    /**
     * Story 5.4's outcome transition — mirrors the {@code Reconciliation}'s
     * outcome onto the PI's own lifecycle status. Auto-confirm is a
     * <strong>system</strong> action (no user), so there's no actor argument;
     * the audit of what/when/against-what-variance lives on the {@code
     * Reconciliation} record and the audit trail (5.7).
     */
    public void markAutoConfirmed() {
        transitionTo(ProformaInvoiceStatus.AUTO_CONFIRMED);
    }

    /** Story 5.4 — routed to approval; the approval mechanics (who, the 2-of-N gate) are 5.5/5.6. */
    public void markRoutedForApproval() {
        transitionTo(ProformaInvoiceStatus.ROUTED_FOR_APPROVAL);
    }

    /**
     * Story 5.5 — a routed PI cleared its approval requirement (a single
     * approver on the non-increase path, or the Nth distinct pool sign-off on
     * a price increase). The immutable {@code ApprovalAction} rows are the
     * audit of who/when/why; this only moves the lifecycle status.
     */
    public void markApproved() {
        transitionTo(ProformaInvoiceStatus.APPROVED);
    }

    /** Story 5.5 — a single rejection is enough to reject a routed PI; the rejecting {@code ApprovalAction} records who/when/why. */
    public void markRejected() {
        transitionTo(ProformaInvoiceStatus.REJECTED);
    }

    /**
     * Story 5.7 — the one place a PI's status changes, guarded by {@link
     * ProformaInvoiceStatus#canTransitionTo}. An illegal transition throws
     * {@code INVALID_STATUS_TRANSITION} rather than corrupting state — so no
     * caller can, say, move a {@code REJECTED} PI to {@code APPROVED}.
     */
    private void transitionTo(ProformaInvoiceStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new ConflictException(ErrorCode.INVALID_STATUS_TRANSITION,
                "Illegal reconciliation status transition: " + status + " → " + target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public String getPiReference() {
        return piReference;
    }

    public String getCurrency() {
        return currency;
    }

    public ProformaInvoiceStatus getStatus() {
        return status;
    }

    /** See the class Javadoc's Cardinality note — the current PI for its PO. */
    public boolean isActive() {
        return active;
    }

    public UUID getLoggedBy() {
        return loggedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
