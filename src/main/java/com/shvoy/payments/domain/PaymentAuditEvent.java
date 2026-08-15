package com.shvoy.payments.domain;

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
 * One immutable entry on a payment's audit trail (Story 6.2) — so a due date
 * is always explicable, and a recalculation is reviewable ("moved because the
 * arrival date changed from X to Y"). Same genuinely-append-only shape as the
 * reconciliation audit trail (5.7): construct-only, and its repository exposes
 * no update or delete path, so there is no code path to alter or remove an
 * entry.
 */
@Entity
@Table(name = "payment_audit_events")
public class PaymentAuditEvent extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private PaymentAuditEventType eventType;

    @Column(name = "detail", length = 2000)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentAuditEvent() {
    }

    public PaymentAuditEvent(UUID paymentId, UUID purchaseOrderId, PaymentAuditEventType eventType, String detail) {
        this.paymentId = paymentId;
        this.purchaseOrderId = purchaseOrderId;
        this.eventType = eventType;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public PaymentAuditEventType getEventType() {
        return eventType;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
