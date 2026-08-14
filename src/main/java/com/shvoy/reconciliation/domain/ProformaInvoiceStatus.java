package com.shvoy.reconciliation.domain;

/**
 * The reconciliation lifecycle — see Story 5.1. {@code LOGGED} is every PI's
 * starting state; the full auto-confirm/route/approve/reject flow is 5.4-5.7's
 * job to actually drive, but the states themselves are declared here from the
 * start so the column never needs a later migration to widen it.
 */
public enum ProformaInvoiceStatus {
    LOGGED,
    AUTO_CONFIRMED,
    ROUTED_FOR_APPROVAL,
    APPROVED,
    REJECTED
}
