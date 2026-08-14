package com.shvoy.reconciliation.domain;

/**
 * The auto-confirm-vs-route decision (Story 5.4) — the outcome the whole
 * feature exists to produce. Recorded on the {@link Reconciliation} and
 * mirrored onto the PI's own {@code status} ({@code AUTO_CONFIRMED}/{@code
 * ROUTED_FOR_APPROVAL}).
 *
 * <ul>
 *   <li>{@code AUTO_CONFIRMED} — every matched line's variance is strictly
 *       within tolerance, with no structural finding and no currency
 *       mismatch. A <strong>system</strong> action, not a user's.</li>
 *   <li>{@code ROUTED_FOR_APPROVAL} — at least one line is at-or-outside
 *       tolerance, or there's a structural finding, or a currency mismatch.
 *       The <em>approval mechanics</em> (who approves, the 2-of-N gate) are
 *       5.5/5.6; this only sets the outcome and the reason.</li>
 * </ul>
 */
public enum ReconciliationOutcome {
    AUTO_CONFIRMED,
    ROUTED_FOR_APPROVAL
}
