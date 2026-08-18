package com.shvoy.dashboard.dto;

import java.util.List;

import com.shvoy.payments.dto.DashboardPaymentRowView;
import com.shvoy.payments.dto.DashboardStatsView;
import com.shvoy.suppliers.dto.SupplierPriceWarning;

/**
 * Everything Screen 1 renders, in one shape (Story 9.1) — the three stat tiles,
 * the capped payment digest, and the (currently empty) alerts slot. Served by a
 * single {@code GET /api/dashboard} so the landing page loads in one round-trip,
 * the same serve-the-screen-in-one-shape discipline as 5.7/6.3.
 *
 * <p>This is an <strong>assembly</strong> — every value comes from an existing
 * operation via {@link com.shvoy.payments.dto.DashboardStatsView} /
 * {@link com.shvoy.payments.dto.DashboardPaymentRowView} (6.3's queue + aggregates,
 * 6.6's open-case count); the dashboard composes and shapes, it never recomputes.
 *
 * <p>{@code priceWarnings} (9.2) is the suppliers whose price files are expired or
 * expiring soon — a capped digest of the same rollup Screen 2 serves uncapped;
 * empty when all's well. Added additively, exactly as the {@code alerts} slot
 * anticipated. {@code alerts} ships as an <strong>empty array from day one</strong>
 * — 9.3's banner slot; shipping the field now makes 9.3 additive, not a contract
 * change.
 */
public record DashboardResponse(
    DashboardStatsView stats,
    List<DashboardPaymentRowView> payments,
    List<SupplierPriceWarning> priceWarnings,
    List<Object> alerts
) {
}
