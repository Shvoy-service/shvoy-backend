package com.shvoy.suppliers.domain;

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

/**
 * "At quantity >= threshold, the unit price is X" — see Story 3.6. Attached
 * to a {@link SkuPrice}, not the {@code Sku} directly: a price change can
 * change the tier structure, and tiers need to stay versioned alongside the
 * price they modify, so historical reconciliation (Feature 5) sees the
 * tiers that applied at the time, not whatever the current ones happen to
 * be. When a price file supersedes an old {@code SkuPrice}, its tiers
 * travel with the new price version, not the old one.
 *
 * Expressed as an absolute unit price per tier, not a percentage discount
 * off the base — simpler, unambiguous, no derived-rounding step. Flagged as
 * the MVP default pending Product Owner confirmation of how suppliers
 * actually quote volume pricing — see docs/CONTRACT.md's Discount tiers
 * section.
 *
 * No currency column: a tier's currency is always its parent SkuPrice's —
 * tiers don't change currency — so it's read from there
 * (DiscountTierService composes the wire-format UnitPrice), never stored
 * redundantly here where it could drift out of sync.
 *
 * Full-replace lifecycle only (see DiscountTierService#setTiers): a row is
 * either freshly created or deleted, never updated in place, so unlike
 * every other tenant-scoped entity in this module there's no
 * {@code updated_at} — nothing here is ever mutated after creation.
 */
@Entity
@Table(name = "discount_tiers")
public class DiscountTier extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "sku_price_id", nullable = false)
    private UUID skuPriceId;

    @Column(name = "quantity_threshold", nullable = false)
    private int quantityThreshold;

    @Column(name = "unit_price_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPriceAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DiscountTier() {
    }

    public DiscountTier(UUID skuPriceId, int quantityThreshold, BigDecimal unitPriceAmount) {
        this.skuPriceId = skuPriceId;
        this.quantityThreshold = quantityThreshold;
        this.unitPriceAmount = unitPriceAmount;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSkuPriceId() {
        return skuPriceId;
    }

    public int getQuantityThreshold() {
        return quantityThreshold;
    }

    public BigDecimal getUnitPriceAmount() {
        return unitPriceAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
