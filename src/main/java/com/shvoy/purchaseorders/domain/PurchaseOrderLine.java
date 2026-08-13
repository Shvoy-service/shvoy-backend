package com.shvoy.purchaseorders.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
import com.shvoy.suppliers.dto.PriceResolutionResult;

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
 * {@code lineTotalAmount} stays nullable with no mutator yet — that's 4.3's
 * job. Everything else ({@code unitPriceAmount}/{@code currency}/
 * {@code appliedTierThreshold}, plus {@code priceFound}/
 * {@code pricedAsOfDate}/{@code cartonValid}/{@code adjustedQuantity}) is
 * set in one shot by {@link #applyPriceResolution}, added in Story 4.2 —
 * see that method's Javadoc.
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

    @Column(name = "price_found")
    private Boolean priceFound;

    @Column(name = "priced_as_of_date")
    private LocalDate pricedAsOfDate;

    @Column(name = "carton_valid")
    private Boolean cartonValid;

    @Column(name = "adjusted_quantity")
    private Integer adjustedQuantity;

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

    /**
     * Applies a 3.8 {@link PriceResolutionResult} as this line's price
     * snapshot — see Story 4.2. This is the only place the line's price/
     * tier/carton/validity state is written, and it always comes from a
     * fresh resolution, never re-derived independently here — same
     * snapshot-not-live-reference principle as the fields themselves (see
     * class Javadoc). Carton fields are set regardless of
     * {@code result.priceFound()} (carton validity doesn't depend on
     * pricing succeeding — see PriceResolutionResult); the price/currency/
     * tier fields are null when no valid price was found, so an expired or
     * absent price file is a carried flag (see {@link #getPriceFound}),
     * never a silent zero-price.
     */
    public void applyPriceResolution(PriceResolutionResult result) {
        this.priceFound = result.priceFound();
        this.unitPriceAmount = result.priceFound() ? result.unitPrice().amount() : null;
        this.currency = result.priceFound() ? result.unitPrice().currency() : null;
        this.appliedTierThreshold = result.appliedTierThreshold();
        this.pricedAsOfDate = result.asOfDate();
        this.cartonValid = result.cartonValid();
        this.adjustedQuantity = result.adjustedQuantity();
        this.updatedAt = Instant.now();
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

    /**
     * Null until {@link #applyPriceResolution} has run at least once
     * (never priced yet); {@code false} means it ran but found no valid
     * price for {@link #getPricedAsOfDate} (expired/absent price file —
     * blocking on this is 4.5's job, this only carries the flag);
     * {@code true} means {@link #getUnitPrice} is populated.
     */
    public Boolean getPriceFound() {
        return priceFound;
    }

    public LocalDate getPricedAsOfDate() {
        return pricedAsOfDate;
    }

    public Boolean getCartonValid() {
        return cartonValid;
    }

    public Integer getAdjustedQuantity() {
        return adjustedQuantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
