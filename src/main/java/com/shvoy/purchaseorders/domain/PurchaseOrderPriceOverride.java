package com.shvoy.purchaseorders.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.shvoy.TenantScoped;

/**
 * An immutable audit record of a Story 4.5 expired-price override — a PO
 * cannot be finalised while any line has no valid price unless a user with
 * authority explicitly overrides it with a reason (Roadmap v2's audit-trail
 * standard: "every override is logged with user, timestamp, and reason —
 * immutable history"). Construct-only, no mutators at all (not even an
 * {@code updatedAt}) — same immutable-audit-record shape as
 * {@code PriceFileUpload} (3.5), deliberately stronger than a log line since
 * this is compliance-facing.
 *
 * The lines actually affected by this override are recorded separately, one
 * {@link PurchaseOrderPriceOverrideLine} row each — a single override event
 * can cover several blocked lines at once.
 */
@Entity
@Table(name = "purchase_order_price_overrides")
public class PurchaseOrderPriceOverride extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "overridden_by", nullable = false)
    private UUID overriddenBy;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PurchaseOrderPriceOverride() {
    }

    public PurchaseOrderPriceOverride(UUID purchaseOrderId, UUID overriddenBy, String reason) {
        this.purchaseOrderId = purchaseOrderId;
        this.overriddenBy = overriddenBy;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public UUID getOverriddenBy() {
        return overriddenBy;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
