package com.shvoy.payments.domain;

/**
 * The kinds of entry on a credit's immutable audit trail (Story 6.7):
 * {@code LOGGED} (created), {@code APPLIED} (matched against an invoice — the
 * detail names the invoice), {@code CANCELLED} (closed — the detail carries the
 * reason).
 */
public enum CreditLedgerAuditEventType {
    LOGGED,
    APPLIED,
    CANCELLED
}
