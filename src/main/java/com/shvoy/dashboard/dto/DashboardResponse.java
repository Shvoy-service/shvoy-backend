package com.shvoy.dashboard.dto;

import java.util.List;

import com.shvoy.payments.dto.DashboardPaymentRowView;
import com.shvoy.payments.dto.DashboardStatsView;

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
 * <p>{@code alerts} ships as an <strong>empty array from day one</strong> — it's
 * 9.3's banner slot. Shipping the field now makes 9.3 additive for the frontend,
 * not a contract change. Price-expiry warnings (9.2) join as a further field when
 * that story defines them, not speculatively.
 */
public record DashboardResponse(
    DashboardStatsView stats,
    List<DashboardPaymentRowView> payments,
    List<Object> alerts
) {
}
