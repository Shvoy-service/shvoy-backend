package com.shvoy.payments.domain;

/**
 * The audit vocabulary for a discrepancy case (Story 6.6). The case history is
 * the discrepancy's paper trail.
 */
public enum DiscrepancyCaseAuditEventType {
    OPENED,
    DETAIL_UPDATED,
    CLAIMED,
    CREDIT_LOGGED,
    RESOLVED,
    DISPUTED
}
