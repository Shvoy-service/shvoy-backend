package com.shvoy.payments.dto;

/**
 * The discrepancy dashboard stat (Story 6.6). {@code openCaseCount} is Screen
 * 1's "Open discrepancies: N" — the count of unresolved discrepancy CASES
 * (OPEN + DISPUTED). This pins the stat's definition (see docs/CONTRACT.md):
 * open discrepancies are cases, not credits. The credit ledger's open-entry
 * count (6.7) is a separate "open credits" ledger view, not this stat.
 */
public record DiscrepancyStatsResponse(
    long openCaseCount
) {
}
