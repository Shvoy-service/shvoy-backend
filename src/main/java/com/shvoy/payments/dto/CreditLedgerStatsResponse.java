package com.shvoy.payments.dto;

/**
 * The credit-ledger dashboard aggregate (Story 6.7) — {@code openCount} is the
 * "Open discrepancies: N" stat, the third Screen-1 stat that 6.3 left stubbed.
 * (Feature 9's dashboard composes it with 6.3's overdue / due-within-5 counts.)
 */
public record CreditLedgerStatsResponse(
    long openCount
) {
}
