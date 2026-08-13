package com.shvoy.suppliers.domain;

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
 * A supplier's product code — see Story 3.4. Deliberately just the SKU's
 * identity (code, description, status, carton size); its price(s) are the
 * separate {@link SkuPrice} entity, and discount tiers (3.6) are separate
 * entities again, same "lean core entity, separate child entities" shape as
 * {@link Supplier}.
 *
 * Entry/upload endpoints, and per-supplier code uniqueness enforcement
 * (the {@code DUPLICATE_SKU} error code), arrived with Story 3.5 — same
 * sequencing as Supplier's own name-uniqueness constraint, which landed
 * with the CRUD endpoints (3.2) rather than the entity (3.1).
 *
 * Carton size (Story 3.7) lives here, not on {@link SkuPrice} — unlike
 * discount tiers, it's a physical property of how the product is packed,
 * not something a new price file should ever change. Nullable: a SKU
 * without one simply isn't subject to the carton-multiple check (see
 * {@link #isCartonMultiple}) — sold loose, no carton constraint.
 */
@Entity
@Table(name = "skus")
public class Sku extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(nullable = false, length = 100)
    private String code;

    @Column(length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SkuStatus status;

    @Column(name = "carton_size")
    private Integer cartonSize;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Sku() {
    }

    public Sku(UUID supplierId, String code, String description) {
        this.supplierId = supplierId;
        this.code = code;
        this.description = description;
        this.status = SkuStatus.ACTIVE;
        this.createdAt = Instant.now();
    }

    /**
     * SKU-level metadata only (Story 3.5, extended by 3.7 to include carton
     * size). Never the price: price changes are new {@link SkuPrice}
     * versions, not edits reachable from here.
     */
    public void update(String code, String description, SkuStatus status, Integer cartonSize) {
        this.code = code;
        this.description = description;
        this.status = status;
        this.cartonSize = cartonSize;
        this.updatedAt = Instant.now();
    }

    /**
     * Whether {@code quantity} can be ordered in whole cartons — the "ok"
     * side of Screen 3's "carton check" column. A null carton size means no
     * constraint applies (a SKU sold loose), so this trivially passes.
     */
    public boolean isCartonMultiple(int quantity) {
        return cartonSize == null || quantity % cartonSize == 0;
    }

    /**
     * The "rounds to N" side of the same check: the carton multiple
     * mathematically nearest to {@code quantity} — not necessarily the
     * multiple above it. E.g. cartonSize 10, quantity 12, rounds to 10 (2
     * away) rather than 20 (8 away), even though 10 under-supplies what was
     * asked for; ties round up. Returns {@code quantity} unchanged when
     * there's no carton size to round against.
     */
    public int nearestCartonMultiple(int quantity) {
        if (cartonSize == null) {
            return quantity;
        }
        int lower = (quantity / cartonSize) * cartonSize;
        int upper = lower + cartonSize;
        int distanceToLower = quantity - lower;
        int distanceToUpper = upper - quantity;
        return distanceToUpper <= distanceToLower ? upper : lower;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public SkuStatus getStatus() {
        return status;
    }

    public Integer getCartonSize() {
        return cartonSize;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
