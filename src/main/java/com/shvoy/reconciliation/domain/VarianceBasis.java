package com.shvoy.reconciliation.domain;

/**
 * <strong>The one open Product Owner question of Feature 5's core, isolated
 * here on purpose.</strong> The variance basis decides what value the
 * variance formula compares — unit price, line total, or both — and so what
 * "reference value" and "PI value" actually are (see {@code
 * VarianceCalculator} and docs/CONTRACT.md's PI reconciliation section).
 *
 * <ul>
 *   <li>{@code UNIT_PRICE} — the built MVP default (recommended): variance is
 *       computed on the per-unit price, with quantity tracked as its own
 *       separate comparison. This keeps the tolerance/approval decision (5.4/
 *       5.5) about <em>price</em> movement — which is what the approval gate
 *       guards ("price increases need sign-off") — and stops a quantity
 *       change from masking or manufacturing a price variance. Matches
 *       Screen 4's layout (Unit price and Quantity as separate rows).</li>
 *   <li>{@code LINE_TOTAL} — the alternative if the Product Owners answer
 *       "line total": variance on unit price × quantity, conflating price and
 *       quantity movement into one number. Implemented behind the same
 *       formula so switching {@code VarianceCalculator}'s constant is a
 *       one-line change, not a rewrite.</li>
 * </ul>
 *
 * "Both" (a fuller picture matching Screen 4) would mean persisting two
 * variances per line rather than one — a shape change to {@code
 * ReconciliationLine}, not just this constant — so it's deliberately not
 * modelled yet; the storage carries a single variance until that answer
 * lands. Whichever basis a comparison used is recorded on its {@code
 * Reconciliation} row, so historical records stay interpretable if the
 * default ever changes.
 */
public enum VarianceBasis {
    UNIT_PRICE,
    LINE_TOTAL
}
