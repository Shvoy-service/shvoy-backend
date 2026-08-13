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

import com.shvoy.Money;
import com.shvoy.TenantScoped;
import com.shvoy.UnitPrice;

/**
 * One SKU/quantity line on a {@link PurchaseOrder} — see Story 4.1.
 *
 * The price fields ({@code unitPriceAmount}, {@code currency},
 * {@code appliedTierThreshold}, {@code lineTotalAmount}) are a
 * <strong>snapshot</strong>, not a live reference to {@code SkuPrice} — the
 * key modelling principle of this story. Once a PO line is priced, that
 * price is fixed regardless of later price-file updates; re-resolving it
 * live would let a PO's price silently drift after the fact, and would
 * break Feature 5 reconciliation, which needs to compare a PI against the
 * price the PO actually carried, not whatever 3.8 would resolve to today.
 * {@code appliedTierThreshold} mirrors {@code PriceResolutionResult}'s
 * field of the same name/meaning: null means the base price applied, not
 * a live reference to a {@code DiscountTier} row (which could later be
 * deleted out from under it by a full-replace tier update — see 3.6).
 *
 * All four price fields are nullable and there's deliberately no mutator
 * for them yet: this story defines the columns, but populating them is
 * 4.2 (resolving via 3.8) and 4.3 (computing the total) — same "field
 * exists before the story that fills it" shape as Sku's carton size (3.7)
 * existed as a column before any endpoint set it. Whichever story adds
 * that mutator also decides its exact shape; not guessed at here.
 */
@Entity
@Table(name = "purchase_order_lines")
public class PurchaseOrderLine extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price_amount", precision = 19, scale = 4)
    private BigDecimal unitPriceAmount;

    @Column(length = 3)
    private String currency;

    @Column(name = "applied_tier_threshold")
    private Integer appliedTierThreshold;

    @Column(name = "line_total_amount", precision = 19, scale = 2)
    private BigDecimal lineTotalAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected PurchaseOrderLine() {
    }

    public PurchaseOrderLine(UUID purchaseOrderId, UUID skuId, int lineNumber, int quantity) {
        this.purchaseOrderId = purchaseOrderId;
        this.skuId = skuId;
        this.lineNumber = lineNumber;
        this.quantity = quantity;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public int getQuantity() {
        return quantity;
    }

    /**
     * Null until 4.2/4.3 price this line — composed from the snapshot
     * columns, same convenience-view pattern as {@code SkuPrice#getUnitPrice}.
     */
    public UnitPrice getUnitPrice() {
        return unitPriceAmount == null ? null : new UnitPrice(unitPriceAmount, currency);
    }

    public Integer getAppliedTierThreshold() {
        return appliedTierThreshold;
    }

    /**
     * Null until 4.3 computes it. Reuses the unit price's currency column
     * rather than storing a second one — a line total is always in the
     * same currency as its own unit price.
     */
    public Money getLineTotal() {
        return lineTotalAmount == null ? null : new Money(lineTotalAmount, currency);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
