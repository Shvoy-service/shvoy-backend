package com.shvoy.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.shvoy.reconciliation.domain.VarianceBasis;

/**
 * The variance arithmetic in isolation — pure, no Spring, no persistence.
 * Proves the formula, the signed direction, and the HALF_EVEN-to-2dp rounding
 * that makes the stored/displayed/compared value identical (Story 5.3).
 */
class VarianceCalculatorTest {

    @Test
    void identicalPriceIsZeroVariance() {
        BigDecimal variance = VarianceCalculator.unitPriceVariancePct(
            new BigDecimal("2.0000"), 10, new BigDecimal("2.0000"), 10);
        assertThat(variance).isEqualByComparingTo("0.00");
    }

    @Test
    void priceIncreaseIsPositiveVariance() {
        // 2.0000 -> 2.2000 is +10%
        BigDecimal variance = VarianceCalculator.unitPriceVariancePct(
            new BigDecimal("2.0000"), 10, new BigDecimal("2.2000"), 10);
        assertThat(variance).isEqualByComparingTo("10.00");
        assertThat(variance.signum()).isEqualTo(1);
    }

    @Test
    void priceDecreaseIsNegativeVariance() {
        // 2.0000 -> 1.8000 is -10%
        BigDecimal variance = VarianceCalculator.unitPriceVariancePct(
            new BigDecimal("2.0000"), 10, new BigDecimal("1.8000"), 10);
        assertThat(variance).isEqualByComparingTo("-10.00");
        assertThat(variance.signum()).isEqualTo(-1);
    }

    @Test
    void varianceIsRoundedHalfEvenToTwoDecimals() {
        // 3.0000 -> 3.1000: 0.1/3 = 3.3333...% -> 3.33 (HALF_EVEN, rounds down here)
        BigDecimal variance = VarianceCalculator.unitPriceVariancePct(
            new BigDecimal("3.0000"), 1, new BigDecimal("3.1000"), 1);
        assertThat(variance).isEqualByComparingTo("3.33");
        assertThat(variance.scale()).isEqualTo(2);
    }

    @Test
    void halfEvenRoundsToEvenNeighbourNotAlwaysUp() {
        // reference 8.0000, value 8.0002 -> 0.0002/8 = 0.0025% -> HALF_EVEN to 2dp = 0.00 (2 is even),
        // where HALF_UP would give 0.01 — proves the mode is HALF_EVEN, not HALF_UP.
        BigDecimal variance = VarianceCalculator.unitPriceVariancePct(
            new BigDecimal("8.0000"), 1, new BigDecimal("8.0002"), 1);
        assertThat(variance).isEqualByComparingTo("0.00");
    }

    @Test
    void unitPriceBasisIgnoresQuantityDifference() {
        // Same unit price, different quantities -> unit-price variance is still 0 under the UNIT_PRICE basis;
        // the quantity movement shows up only in the separate quantity comparison.
        assertThat(VarianceCalculator.BASIS).isEqualTo(VarianceBasis.UNIT_PRICE);
        BigDecimal variance = VarianceCalculator.unitPriceVariancePct(
            new BigDecimal("2.0000"), 10, new BigDecimal("2.0000"), 25);
        assertThat(variance).isEqualByComparingTo("0.00");
    }

    @Test
    void nullPriceYieldsNullVariance() {
        assertThat(VarianceCalculator.unitPriceVariancePct(null, 10, new BigDecimal("2.0000"), 10)).isNull();
        assertThat(VarianceCalculator.unitPriceVariancePct(new BigDecimal("2.0000"), 10, null, 10)).isNull();
    }

    @Test
    void zeroReferenceYieldsNullVariance() {
        assertThat(VarianceCalculator.unitPriceVariancePct(
            new BigDecimal("0.0000"), 10, new BigDecimal("2.0000"), 10)).isNull();
    }

    @Test
    void quantityVarianceCarriesPercentageAndAbsoluteWithDirection() {
        // PO 10 -> PI 12: +2 absolute, +20%
        assertThat(VarianceCalculator.quantityVarianceAbs(10, 12)).isEqualTo(2);
        assertThat(VarianceCalculator.quantityVariancePct(10, 12)).isEqualByComparingTo("20.00");

        // PO 10 -> PI 8: -2 absolute, -20%
        assertThat(VarianceCalculator.quantityVarianceAbs(10, 8)).isEqualTo(-2);
        assertThat(VarianceCalculator.quantityVariancePct(10, 8)).isEqualByComparingTo("-20.00");
    }
}
