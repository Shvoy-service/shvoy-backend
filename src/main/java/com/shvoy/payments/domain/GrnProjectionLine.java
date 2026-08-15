package com.shvoy.payments.domain;

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
 * A payments-local projection of one received per-SKU quantity from a goods
 * receipt (Story 6.5). The GRN itself lives in {@code shipments}; payments can't
 * pull it (that would make the module graph cyclic), so it projects the
 * quantities carried on the {@code ProvisionalGoodsReceiptEvent} and the
 * three-way match reads the GRN leg from here. Full-replaced per consignment
 * each time the event is (re-)published.
 */
@Entity
@Table(name = "payment_grn_projection_lines")
public class GrnProjectionLine extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "consignment_id", nullable = false)
    private UUID consignmentId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "received_quantity", nullable = false)
    private int receivedQuantity;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GrnProjectionLine() {
    }

    public GrnProjectionLine(UUID purchaseOrderId, UUID consignmentId, UUID skuId, int receivedQuantity) {
        this.purchaseOrderId = purchaseOrderId;
        this.consignmentId = consignmentId;
        this.skuId = skuId;
        this.receivedQuantity = receivedQuantity;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
