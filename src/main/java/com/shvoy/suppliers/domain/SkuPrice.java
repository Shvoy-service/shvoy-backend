package com.shvoy.suppliers.domain;

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

import com.shvoy.TenantScoped;
import com.shvoy.UnitPrice;

/**
 * One validity-windowed price for a SKU — see Story 3.4. A SKU has a
 * <em>history</em> of these, not a single mutable current price: Feature 5
 * (PO/price-file reconciliation) needs to resolve "the price valid on the
 * order's date", not just "the price today", and a single-current-price
 * model loses that the moment a new price file supersedes an old one. This
 * costs more now (multiple rows per SKU instead of one) but avoids a
 * retrofit once Feature 5 depends on historical lookup. Deliberate,
 * flagged choice — see docs/CONTRACT.md's SKU & price model section.
 *
 * {@code validTo} is nullable: an open-ended price stays valid until a
 * later price file supersedes it (or a specific end date is set), rather
 * than requiring every price to declare its own expiry up front. Resolving
 * "which price applies on date X" across overlapping/superseding rows is
 * the price-resolution service's job (3.8), not this entity's — this story
 * is the model only.
 *
 * unit price + currency are plain columns here, not an embedded
 * {@link UnitPrice}, matching this codebase's existing style (no JPA
 * embeddables/relationships anywhere yet — see Supplier, PaymentTerms) —
 * {@link #getUnitPrice()} is the convenience view for callers that want the
 * wire-format type.
 */
@Entity
@Table(name = "sku_prices")
public class SkuPrice extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "unit_price_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPriceAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected SkuPrice() {
    }

    public SkuPrice(UUID skuId, UnitPrice unitPrice, LocalDate validFrom, LocalDate validTo) {
        this.skuId = skuId;
        this.unitPriceAmount = unitPrice.amount();
        this.currency = unitPrice.currency();
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.createdAt = Instant.now();
    }

    /**
     * Derived, never stored — the wireframe's "in-date/expired" status is a
     * function of today's date against the validity window, not a flag
     * that could drift out of sync with it. {@code asOf} rather than an
     * implicit {@code LocalDate.now()} so callers (and tests) control the
     * reference date explicitly.
     */
    public boolean isInDate(LocalDate asOf) {
        return !asOf.isBefore(validFrom) && (validTo == null || !asOf.isAfter(validTo));
    }

    /**
     * Bounds this row's window — Story 3.5's auto-close step of adding a
     * later price: the prior open row is closed the day before the new
     * row's {@code validFrom}, never mutated in value. Not a general setter
     * — SkuService is the only caller, as part of the supersession rule.
     */
    public void close(LocalDate validTo) {
        this.validTo = validTo;
        this.updatedAt = Instant.now();
    }

    public UnitPrice getUnitPrice() {
        return new UnitPrice(unitPriceAmount, currency);
    }

    public UUID getId() {
        return id;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
