package com.shvoy.payments.dto;

import org.springframework.modulith.NamedInterface;

/**
 * The three Screen-1 stat tiles (Story 9.1), the {@code payments} module's
 * contribution to the dashboard — composed from the <em>existing</em> operations
 * (6.3's two payment aggregates + 6.6's open-case count), never recomputed. A
 * narrow cross-module read the {@code dashboard} module assembles into its
 * response. Field names mirror the Screen-1 contract exactly.
 */
@NamedInterface("payment-dashboard")
public record DashboardStatsView(
    long overduePayments,
    long dueWithinFiveDays,
    long openDiscrepancies
) {
}
