package com.shvoy.shipments.domain;

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
 * One immutable entry on a shipment document's audit trail (Story 7.2) — so a
 * BL/ex-factory date that moved a payment's due date is always explicable, and
 * a corrected reference is reviewable. Same genuinely-append-only shape as the
 * payment (6.2) and reconciliation (5.7) audit trails: construct-only, and its
 * repository exposes no update or delete path.
 *
 * <p>{@code consignmentId} is nullable because BL and ex-factory changes are
 * shipment-level (they can affect several consignments); packing-list and
 * inspection-report changes name the specific consignment.
 */
@Entity
@Table(name = "shipment_document_audit_events")
public class ShipmentDocumentAuditEvent extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shipment_id", nullable = false)
    private UUID shipmentId;

    @Column(name = "consignment_id")
    private UUID consignmentId;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private ShipmentDocumentAuditEventType eventType;

    @Column(name = "detail", length = 2000)
    private String detail;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ShipmentDocumentAuditEvent() {
    }

    public ShipmentDocumentAuditEvent(UUID shipmentId, UUID consignmentId, UUID purchaseOrderId,
            ShipmentDocumentAuditEventType eventType, String detail, UUID createdBy) {
        this.shipmentId = shipmentId;
        this.consignmentId = consignmentId;
        this.purchaseOrderId = purchaseOrderId;
        this.eventType = eventType;
        this.detail = detail;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getShipmentId() {
        return shipmentId;
    }

    public UUID getConsignmentId() {
        return consignmentId;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public ShipmentDocumentAuditEventType getEventType() {
        return eventType;
    }

    public String getDetail() {
        return detail;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
