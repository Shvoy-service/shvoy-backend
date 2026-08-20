package com.shvoy.containerfill.domain;

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
 * An immutable audit row for a {@link ContainerFillOffer} transition (Story 8.1)
 * — append-only, construct-only. Same shape as {@code DiscrepancyCaseAuditEvent}:
 * a stamped actor (nullable for any future system action) and a human-readable
 * detail line embedding the reason.
 */
@Entity
@Table(name = "container_fill_offer_audit_events")
public class ContainerFillOfferAuditEvent extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "offer_id", nullable = false)
    private UUID offerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private ContainerFillOfferAuditEventType eventType;

    @Column(name = "detail", length = 2000)
    private String detail;

    @Column(name = "actor")
    private UUID actor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ContainerFillOfferAuditEvent() {
    }

    public ContainerFillOfferAuditEvent(UUID offerId, ContainerFillOfferAuditEventType eventType, String detail, UUID actor) {
        this.offerId = offerId;
        this.eventType = eventType;
        this.detail = detail;
        this.actor = actor;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOfferId() {
        return offerId;
    }

    public ContainerFillOfferAuditEventType getEventType() {
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
