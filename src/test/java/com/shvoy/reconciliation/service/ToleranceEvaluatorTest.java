package com.shvoy.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * The tolerance boundary in isolation — pure, no Spring. The exact-at-
 * tolerance case is the single most important assertion in Feature 5: it
 * encodes the EXCLUSIVE-boundary rule that took three rounds of Product Owner
 * questions to settle, and it's the one that would otherwise silently regress
 * if someone changed {@code <} to {@code <=}.
 */
class ToleranceEvaluatorTest {

    private static final BigDecimal TWO_PERCENT = new BigDecimal("2.00");

    @Test
    void varianceJustBelowToleranceIsWithin() {
        assertThat(ToleranceEvaluator.isWithinTolerance(new BigDecimal("1.99"), TWO_PERCENT)).isTrue();
    }

    @Test
    void varianceExactlyAtToleranceIsOutside_theExclusiveBoundaryRule() {
        // 2.00% against a 2% tolerance is OUTSIDE — auto-confirm requires strictly below the threshold.
        // If this ever fails, someone changed the boundary from exclusive (<) to inclusive (<=).
        assertThat(ToleranceEvaluator.isWithinTolerance(new BigDecimal("2.00"), TWO_PERCENT)).isFalse();
    }

    @Test
    void varianceJustAboveToleranceIsOutside() {
        assertThat(ToleranceEvaluator.isWithinTolerance(new BigDecimal("2.01"), TWO_PERCENT)).isFalse();
    }

    @Test
    void zeroVarianceIsWithinAnyPositiveTolerance() {
        assertThat(ToleranceEvaluator.isWithinTolerance(new BigDecimal("0.00"), TWO_PERCENT)).isTrue();
    }

    @Test
    void comparisonIsAgainstTheGivenScaleWithoutReRounding() {
        // 2.0 and 2.00 are numerically equal — compareTo ignores scale, so an exactly-equal
        // variance is outside regardless of how the operands were scaled by 5.3.
        assertThat(ToleranceEvaluator.isWithinTolerance(new BigDecimal("2.0"), new BigDecimal("2.00"))).isFalse();
    }
}
