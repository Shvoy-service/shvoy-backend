package com.shvoy.containerfill.domain;

/**
 * The audited transitions of a {@link ContainerFillOffer}. {@code FLAGGED}/{@code
 * CANCELLED} are 8.1; {@code DEADLINE_SET}/{@code DEADLINE_REVISED}/{@code
 * REMINDER_SENT} are 8.2 (the deadline + reminder); the decision (8.3) appends its own.
 */
public enum ContainerFillOfferAuditEventType {
    FLAGGED,
    CANCELLED,
    DEADLINE_SET,
    DEADLINE_REVISED,
    REMINDER_SENT
}
