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
 * A supplier's confirmed proforma invoice, logged against a {@code
 * PurchaseOrder} — see Story 5.1. Data model only: no logging endpoint
 * (5.2), variance calculation (5.3), tolerance/auto-confirm (5.4), approval
 * routing (5.5/5.6), or the full status-lifecycle/audit trail (5.7) exist
 * yet, so this is currently reachable only via direct repository access,
 * same shape as PurchaseOrder before 4.4.
 *
 * {@code purchaseOrderId} is a plain {@code UUID}, not a JPA relationship —
 * matching this codebase's flat-column convention throughout (see
 * PurchaseOrder, Supplier, Sku).
 *
 * <strong>Cardinality:</strong> a PO can receive more than one PI over
 * time — a supplier re-issuing a corrected proforma is a real scenario, so
 * this is one-to-many rather than strictly one-per-PO. {@code active}
 * identifies the current one: exactly one PI per PO is active at a time
 * (superseded PIs are kept, never deleted, for audit). This story only
 * establishes the field — the actual supersession step (marking a prior PI
 * inactive when a correction is logged) belongs to 5.2, the first story
 * with a real caller for it, same split as {@code SkuPrice}'s validity
 * window (modelled in 3.4, actually superseded by 3.5's upload logic).
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
