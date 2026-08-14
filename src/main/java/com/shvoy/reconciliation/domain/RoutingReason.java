package com.shvoy.reconciliation.domain;

/**
 * Why a PI was routed to approval rather than auto-confirmed (Story 5.4) —
 * so the approver sees why it's in front of them and Screen 4 can explain the
 * status. More than one can apply at once (a PI can be both structurally
 * mismatched and outside tolerance), hence surfaced as a set.
 *
 * <p>Derived from the stored comparison and the recorded tolerance
 * ({@code Reconciliation#toleranceApplied}) rather than denormalised into its
 * own column: every input is already persisted (each line's variance and
 * finding type, plus the header's currency-mismatch flag and the tolerance
 * that was applied), so the reason is a deterministic, reproducible function
 * of the immutable record — nothing to drift out of sync with it.
 */
public enum RoutingReason {
    VARIANCE_OUTSIDE_TOLERANCE,
    STRUCTURAL_FINDING,
    CURRENCY_MISMATCH
}
