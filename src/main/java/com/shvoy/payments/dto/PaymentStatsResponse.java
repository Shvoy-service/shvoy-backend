package com.shvoy.payments.dto;

/**
 * The payment-derived dashboard stats (Story 6.3, Screen 1) — a lightweight
 * aggregate so the dashboard doesn't fetch the full queue to count it.
 *
 * <ul>
 *   <li>{@code overdueCount} — payments whose due date is strictly before
 *       today and aren't {@code PAID}.</li>
 *   <li>{@code dueWithin5DaysCount} — payments due in {@code [today, today+5]}
 *       (inclusive of day 5), not {@code PAID}.</li>
 * </ul>
 *
 * The dashboard's third stat, "Open discrepancies", is deliberately not here —
 * it belongs to the credit ledger (6.6/6.7), which doesn't exist yet.
 */
public record PaymentStatsResponse(
    long overdueCount,
    long dueWithin5DaysCount
) {
}
