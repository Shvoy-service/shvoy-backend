package com.shvoy.purchaseorders.domain;

import java.time.Instant;
import java.time.LocalDate;
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
 * A purchase order to a single supplier — see Story 4.1. Data model only:
 * no create/edit endpoints exist yet (4.4), so this entity is currently
 * reachable only via direct repository access (tests) or a future story's
 * service layer, same shape as Supplier before 3.2 or Sku before 3.5.
 *
 * {@code poNumber} is assigned by {@link com.shvoy.purchaseorders.service.PoNumberGenerator}
 * at creation time — a per-company sequential "PO-0001" style reference,
 * not a UUID-derived code, matching what the wireframes show and what
 * users expect to reference a PO by. Unique per company (see V18), not
 * globally, since two different companies each having a "PO-0001" is
 * correct, not a collision.
 *
 * {@code createdBy} is a plain {@code UUID} referencing {@code users.id} —
 * no JPA relationship, matching this codebase's flat-column convention
 * throughout (see Supplier, Sku).
 */
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "po_number", nullable = false, length = 50)
    private String poNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseOrderStatus status;

    @Column(name = "requested_etd")
    private LocalDate requestedEtd;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected PurchaseOrder() {
    }

    public PurchaseOrder(UUID supplierId, String poNumber, UUID createdBy) {
        this.supplierId = supplierId;
        this.poNumber = poNumber;
        this.status = PurchaseOrderStatus.DRAFT;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public String getPoNumber() {
        return poNumber;
    }

    public PurchaseOrderStatus getStatus() {
        return status;
    }

    public LocalDate getRequestedEtd() {
        return requestedEtd;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
