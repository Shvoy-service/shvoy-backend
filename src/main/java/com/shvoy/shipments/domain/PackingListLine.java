package com.shvoy.shipments.domain;

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
 * One per-SKU quantity on a consignment's packing list (Story 7.4) — the
 * itemised "what shipped" that Story 7.2 deferred as not-yet-needed and the
 * provisional GRN now requires. Full-replaced whenever the packing list is
 * (re)logged. Entered manually at MVP; the AI extraction layer feeds the same
 * shape later.
 */
@Entity
@Table(name = "shipment_packing_list_lines")
public class PackingListLine extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "consignment_id", nullable = false)
    private UUID consignmentId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PackingListLine() {
    }

    public PackingListLine(UUID consignmentId, UUID skuId, int quantity) {
        this.consignmentId = consignmentId;
        this.skuId = skuId;
        this.quantity = quantity;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getConsignmentId() {
        return consignmentId;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
