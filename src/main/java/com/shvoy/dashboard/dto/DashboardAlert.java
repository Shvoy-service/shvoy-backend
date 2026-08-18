package com.shvoy.dashboard.dto;

/**
 * One Screen-1 banner alert (Story 9.3), derived read-time from existing system
 * state — no alert entity, no lifecycle, no acknowledge/dismiss. It disappears
 * the moment its condition clears (fix the pool, the banner empties on next
 * load); that statelessness is the anti-framework decision.
 *
 * <p>{@code code} is the stable contract (see {@link AlertCode}); {@code message}
 * is a human-readable fallback, not typed against. {@code link} is a frontend
 * route hint (a relative path the frontend owns) — where to go fix it.
 */
public record DashboardAlert(
    AlertCode code,
    AlertSeverity severity,
    String message,
    String link
) {
}
