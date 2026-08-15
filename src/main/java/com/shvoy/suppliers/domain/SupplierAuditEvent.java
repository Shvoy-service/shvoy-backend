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
 * One immutable entry on a supplier's audit trail (supplier remodel) — validation
 * decisions, target-term activations, and the loud bank-details-change/revert.
 * Construct-only, no delete path, same shape as the other audit trails.
 */
@Entity
@Table(name = "supplier_audit_events")
public class SupplierAuditEvent extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private SupplierAuditEventType eventType;

    @Column(name = "detail", length = 2000)
    private String detail;

    @Column(name = "actor")
    private UUID actor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SupplierAuditEvent() {
    }

    public SupplierAuditEvent(UUID supplierId, SupplierAuditEventType eventType, String detail, UUID actor) {
        this.supplierId = supplierId;
        this.eventType = eventType;
        this.detail = detail;
        this.actor = actor;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public SupplierAuditEventType getEventType() {
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
