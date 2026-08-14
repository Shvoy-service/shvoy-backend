package com.shvoy.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The variance/tolerance half of the shared fixture
 * (src/test/resources/fixtures/money-tolerance-fixture.json) — package-local so it can
 * reach {@link VarianceCalculator}/{@link ToleranceEvaluator} directly, same as
 * {@code VarianceCalculatorTest}. The Money-level half (rounding, order totals, deposit
 * split) is asserted by {@code com.shvoy.MoneyToleranceFixtureTest}.
 *
 * <p>Every case here is EXCLUSIVE-boundary (<code>|variance| &lt; tolerance</code>) and
 * compares the variance exactly as {@link VarianceCalculator} rounds it (HALF_EVEN, 2dp)
 * — never a raw value — per docs/CONTRACT.md's "Tolerance evaluation & auto-confirm"
 * section. Some cases exist specifically because the HALF_EVEN rounding step itself moves
 * a raw value across the boundary; see each case's {@code rawVariancePercent}/description.
 */
class VarianceToleranceFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Fixture loadFixture() throws IOException {
        try (InputStream in = VarianceToleranceFixtureTest.class.getClassLoader()
                .getResourceAsStream("fixtures/money-tolerance-fixture.json")) {
            if (in == null) {
                throw new IllegalStateException("fixtures/money-tolerance-fixture.json not found on the test classpath");
            }
            return MAPPER.readValue(in, Fixture.class);
        }
    }

    @Test
    void varianceAndToleranceMatchTheFixture() throws IOException {
        for (VarianceCase c : loadFixture().varianceTolerance()) {
            BigDecimal variance = VarianceCalculator.unitPriceVariancePct(
                new BigDecimal(c.poUnitPrice()), c.poQuantity(),
                new BigDecimal(c.piUnitPrice()), c.piQuantity());

            assertThat(variance)
                .as("%s (variance)", c.description())
                .isEqualByComparingTo(c.expectedVariancePercent());

            boolean within = ToleranceEvaluator.isWithinTolerance(
                variance.abs(), new BigDecimal(c.tolerancePercent()));

            assertThat(within)
                .as("%s (within tolerance)", c.description())
                .isEqualTo(c.withinTolerance());

            if (c.expectedQuantityVariancePercent() != null) {
                assertThat(VarianceCalculator.quantityVariancePct(c.poQuantity(), c.piQuantity()))
                    .as("%s (quantity variance %%)", c.description())
                    .isEqualByComparingTo(c.expectedQuantityVariancePercent());
            }
            if (c.expectedQuantityVarianceAbs() != null) {
                assertThat(VarianceCalculator.quantityVarianceAbs(c.poQuantity(), c.piQuantity()))
                    .as("%s (quantity variance abs)", c.description())
                    .isEqualTo(c.expectedQuantityVarianceAbs());
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Fixture(List<VarianceCase> varianceTolerance) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VarianceCase(
        String description,
        String poUnitPrice, int poQuantity,
        String piUnitPrice, int piQuantity,
        String tolerancePercent,
        String expectedVariancePercent,
        boolean withinTolerance,
        String expectedQuantityVariancePercent,
        Integer expectedQuantityVarianceAbs
    ) {
    }
}
