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
 * One per-SKU received quantity on a consignment's provisional GRN (Story 7.4)
 * — a <strong>snapshot</strong> taken from the packing list at receipt time,
 * not a live reference (same provenance principle as PO line prices and payment
 * amounts). These quantities are the substance the three-way match (6.5)
 * compares (PO qty = PI qty = <strong>GRN qty</strong>); a GRN that were only a
 * status flag would let the match certify against nothing.
 *
 * <p>Full-replaced by a deliberate, audited amendment while the GRN is still
 * provisional; once arrival is confirmed (7.6) the GRN is settled history.
 */
@Entity
@Table(name = "shipment_goods_receipt_lines")
public class GoodsReceiptLine extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "consignment_id", nullable = false)
    private UUID consignmentId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "received_quantity", nullable = false)
    private int receivedQuantity;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected GoodsReceiptLine() {
    }

    public GoodsReceiptLine(UUID consignmentId, UUID skuId, int receivedQuantity) {
        this.consignmentId = consignmentId;
        this.skuId = skuId;
        this.receivedQuantity = receivedQuantity;
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

    public int getReceivedQuantity() {
        return receivedQuantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
