package com.shvoy.purchaseorders.domain;

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

/** Append-only trail for a PO's advisory issuance flags (PO-issuance gate) — construct-only, no delete path. */
@Entity
@Table(name = "purchase_order_audit_events")
public class PurchaseOrderAuditEvent extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private PurchaseOrderAuditEventType eventType;

    @Column(name = "detail", length = 2000)
    private String detail;

    @Column(name = "actor")
    private UUID actor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PurchaseOrderAuditEvent() {
    }

    public PurchaseOrderAuditEvent(UUID purchaseOrderId, PurchaseOrderAuditEventType eventType, String detail,
            UUID actor) {
        this.purchaseOrderId = purchaseOrderId;
        this.eventType = eventType;
        this.detail = detail;
        this.actor = actor;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public PurchaseOrderAuditEventType getEventType() {
        return eventType;
    }

    public String getDetail() {
        return detail;
    }

    public UUID getActor() {
        return actor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
