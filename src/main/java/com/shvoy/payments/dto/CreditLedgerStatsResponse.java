package com.shvoy.payments.dto;

/**
 * The credit-ledger aggregate (Story 6.7) — {@code openCount} is the number of
 * <strong>open credits</strong> (a ledger view). It is <em>not</em> the Screen-1
 * "Open discrepancies" stat: 6.6 pinned that to open discrepancy <em>cases</em>
 * ({@code DiscrepancyStatsResponse}). Kept as its own useful ledger count.
 */
public record CreditLedgerStatsResponse(
    long openCount
) {
}
