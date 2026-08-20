package com.shvoy.containerfill.domain;

/**
 * The audited transitions of a {@link ContainerFillOffer}. Only {@code FLAGGED}
 * and {@code CANCELLED} occur in 8.1; the later stories that own the deadline
 * (8.2) and the decision (8.3) append their own event types.
 */
public enum ContainerFillOfferAuditEventType {
    FLAGGED,
    CANCELLED
}
