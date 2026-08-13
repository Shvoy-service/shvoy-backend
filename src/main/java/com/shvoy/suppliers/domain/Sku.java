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
 * identity (code, description, status); its price(s) are the separate
 * {@link SkuPrice} entity, and discount tiers (3.6)/carton size (3.7) are
 * separate entities again, same "lean core entity, separate child entities"
 * shape as {@link Supplier}.
 *
 * Entry/upload endpoints, and per-supplier code uniqueness enforcement
 * (the {@code DUPLICATE_SKU} error code), arrived with Story 3.5 — same
 * sequencing as Supplier's own name-uniqueness constraint, which landed
 * with the CRUD endpoints (3.2) rather than the entity (3.1).
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
     * SKU-level metadata only (Story 3.5) — code, description, status.
     * Never the price: price changes are new {@link SkuPrice} versions, not
     * edits reachable from here.
     */
    public void update(String code, String description, SkuStatus status) {
        this.code = code;
        this.description = description;
        this.status = status;
        this.updatedAt = Instant.now();
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
