package com.shvoy.payments.domain;

/**
 * A discrepancy case's lifecycle (Story 6.6): {@code OPEN} when the match blocks
 * a payment; {@code RESOLVED} once the mismatch is settled (see {@link
 * DiscrepancyResolutionType} for how); {@code DISPUTED} when the invoice is
 * contested outright — the payment stays BLOCKED and this is the seam a future
 * NCR/dispute-letter flow would hang from.
 */
public enum DiscrepancyStatus {
    OPEN,
    RESOLVED,
    DISPUTED
}
