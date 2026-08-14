package com.shvoy.purchaseorders.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.shvoy.TenantScoped;
import com.shvoy.UnitPrice;

/**
 * One blocked line's manually-supplied price within a {@link
 * PurchaseOrderPriceOverride} event — Story 4.5's built default for "what
 * price does an overridden missing-price line use" (option (a) of the
 * question flagged pending Product Owner confirmation: require the
 * overriding user to state a real price, rather than reusing a stale one or
 * leaving the line unpriced — see docs/CONTRACT.md). Immutable, same as its
 * parent override record.
 *
 * Deliberately a flat {@code manualPriceAmount}/{@code manualPriceCurrency}
 * pair rather than a {@code UnitPrice} embeddable — same no-embeddables
 * convention as every other price snapshot in this codebase (see
 * {@code PurchaseOrderLine}). {@link #getManualPrice} composes the two back
 * into a {@code UnitPrice} for callers, same convenience-view pattern as
 * {@code PurchaseOrderLine#getUnitPrice}.
 */
@Entity
@Table(name = "purchase_order_price_override_lines")
public class PurchaseOrderPriceOverrideLine extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "override_id", nullable = false)
    private UUID overrideId;

    @Column(name = "purchase_order_line_id", nullable = false)
    private UUID purchaseOrderLineId;

    @Column(name = "manual_price_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal manualPriceAmount;

    @Column(name = "manual_price_currency", nullable = false, length = 3)
    private String manualPriceCurrency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PurchaseOrderPriceOverrideLine() {
    }

    public PurchaseOrderPriceOverrideLine(UUID overrideId, UUID purchaseOrderLineId, UnitPrice manualPrice) {
        this.overrideId = overrideId;
        this.purchaseOrderLineId = purchaseOrderLineId;
        this.manualPriceAmount = manualPrice.amount();
        this.manualPriceCurrency = manualPrice.currency();
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOverrideId() {
        return overrideId;
    }

    public UUID getPurchaseOrderLineId() {
        return purchaseOrderLineId;
    }

    public UnitPrice getManualPrice() {
        return new UnitPrice(manualPriceAmount, manualPriceCurrency);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
