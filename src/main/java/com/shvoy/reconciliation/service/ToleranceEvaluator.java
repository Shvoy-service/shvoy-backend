package com.shvoy.reconciliation.service;

import java.math.BigDecimal;

/**
 * The tolerance boundary comparison, isolated in one place on purpose — this
 * single operator is the decision three rounds of Product Owner questions
 * were spent pinning down, and the thing a later edit could silently flip.
 *
 * <p><strong>The boundary is EXCLUSIVE.</strong> A variance is within
 * tolerance <em>if and only if</em> its magnitude is strictly less than the
 * tolerance:
 *
 * <pre>{@code within  ⟺  |variance| < tolerance}</pre>
 *
 * So a variance of exactly 2.00% against a 2% tolerance is <em>outside</em>
 * tolerance and routes to approval — auto-confirm requires strictly below the
 * threshold. The comparison is on the magnitude (absolute variance); the
 * variance's direction (its sign) is retained separately for 5.5's
 * asymmetric increase-vs-decrease gate, not consulted here.
 *
 * <p>Both operands are the values as 5.3 already rounded them (HALF_EVEN, 2dp)
 * — this never re-rounds and never compares against an unrounded value, so
 * the displayed variance and the compared variance are the same number, which
 * is exactly what stops the frontend and backend disagreeing at the boundary.
 */
final class ToleranceEvaluator {

    private ToleranceEvaluator() {
    }

    /**
     * @param varianceMagnitude the absolute variance %, as rounded by 5.3
     * @param tolerance the effective tolerance % for the account
     * @return true iff strictly within tolerance (exclusive boundary)
     */
    static boolean isWithinTolerance(BigDecimal varianceMagnitude, BigDecimal tolerance) {
        return varianceMagnitude.compareTo(tolerance) < 0; // EXCLUSIVE: strictly '<', never '<='
    }
}
