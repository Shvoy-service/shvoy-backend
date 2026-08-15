package com.shvoy.payments.domain;

/**
 * The invoice lifecycle (Story 6.4). Minimal for now: {@code LOGGED} is every
 * invoice's starting state; {@code SUPERSEDED} marks one replaced by a
 * corrected re-issue (the {@code active} flag identifies the current one). The
 * match/blocking states the invoice participates in live on the payments it
 * gates, not here.
 */
public enum InvoiceStatus {
    LOGGED,
    SUPERSEDED
}
