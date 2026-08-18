package com.shvoy.shipments.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.shvoy.TenantScoped;

/**
 * One entry in a consignment's confirmed-ETD history (Story 7.5) — append-only.
 * ETDs slip, so revisions are the norm: each records the confirmed date, who
 * changed it, when, and an optional free-text reason. The latest entry is the
 * current value; the trail is the story (the substrate Phase 2's proactive
 * chasing and any supplier-reliability picture read). No anchor, no payment
 * interaction — ETD is a logistics estimate, not a due-date driver.
 */
@Entity
@Table(name = "shipment_etd_revisions")
public class EtdRevision extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "consignment_id", nullable = false)
    private UUID consignmentId;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "confirmed_etd", nullable = false)
    private LocalDate confirmedEtd;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "changed_by", nullable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected EtdRevision() {
    }

    public EtdRevision(UUID consignmentId, UUID purchaseOrderId, LocalDate confirmedEtd, String reason, UUID changedBy) {
        this.consignmentId = consignmentId;
        this.purchaseOrderId = purchaseOrderId;
        this.confirmedEtd = confirmedEtd;
        this.reason = reason;
        this.changedBy = changedBy;
        this.changedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getConsignmentId() {
        return consignmentId;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public LocalDate getConfirmedEtd() {
        return confirmedEtd;
    }

    public String getReason() {
        return reason;
    }

    public UUID getChangedBy() {
        return changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
