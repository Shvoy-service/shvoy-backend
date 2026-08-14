package com.shvoy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shvoy.suppliers.domain.AnchorEvent;
import com.shvoy.suppliers.domain.PaymentSplit;
import com.shvoy.suppliers.domain.PaymentTerms;

/**
 * Asserts the shared fixture (src/test/resources/fixtures/money-tolerance-fixture.json)
 * against the real {@link Money}/{@link UnitPrice}/{@link PaymentTerms} implementations —
 * the Money-level half of the fixture; the variance/tolerance half lives in
 * {@code com.shvoy.reconciliation.service.VarianceToleranceFixtureTest}, which needs
 * package-private access to {@code VarianceCalculator}/{@code ToleranceEvaluator}.
 *
 * <p>This is the deliverable Story 0.1 (frontend) exists to protect: prose in
 * docs/CONTRACT.md can't by itself stop this repo and shvoy-frontend from quietly
 * disagreeing about a rounding step. This test, and its frontend counterpart loading an
 * exact copy of the same file, are what turn that disagreement into a CI failure instead
 * of a live reconciliation surprise.
 */
class MoneyToleranceFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CURRENCY = "USD";

    private static Fixture loadFixture() throws IOException {
        try (InputStream in = MoneyToleranceFixtureTest.class.getClassLoader()
                .getResourceAsStream("fixtures/money-tolerance-fixture.json")) {
            if (in == null) {
                throw new IllegalStateException("fixtures/money-tolerance-fixture.json not found on the test classpath");
            }
            return MAPPER.readValue(in, Fixture.class);
        }
    }

    @Test
    void unitPriceRoundingMatchesTheFixture() throws IOException {
        for (RoundingCase c : loadFixture().unitPriceRounding()) {
            UnitPrice price = new UnitPrice(new BigDecimal(c.input()), CURRENCY);
            assertThat(price.amount())
                .as("unitPriceRounding: %s -> %s", c.input(), c.expected())
                .isEqualByComparingTo(c.expected());
        }
    }

    @Test
    void lineTotalRoundingMatchesTheFixture() throws IOException {
        for (LineTotalRoundingCase c : loadFixture().lineTotalRounding()) {
            if (c.scale() == 2) {
                Money money = new Money(new BigDecimal(c.input()), CURRENCY);
                assertThat(money.amount())
                    .as("lineTotalRounding: %s -> %s", c.input(), c.expected())
                    .isEqualByComparingTo(c.expected());
            } else {
                // Money is fixed at scale 2 — the scale:0 fixture case ("2.5" -> "2")
                // exercises HALF_EVEN generically rather than through Money itself.
                BigDecimal rounded = new BigDecimal(c.input()).setScale(c.scale(), RoundingMode.HALF_EVEN);
                assertThat(rounded)
                    .as("lineTotalRounding: %s -> %s (scale %d)", c.input(), c.expected(), c.scale())
                    .isEqualByComparingTo(c.expected());
            }
        }
    }

    @Test
    void orderTotalsMatchTheFixture() throws IOException {
        for (OrderTotalCase c : loadFixture().orderTotals()) {
            Money total = c.roundedLines().stream()
                .map(amount -> new Money(new BigDecimal(amount), CURRENCY))
                .reduce(Money::plus)
                .orElseThrow();
            assertThat(total.amount())
                .as(c.description())
                .isEqualByComparingTo(c.correctOrderTotal());
        }
    }

    @Test
    void depositBalanceSplitMatchesTheFixture() throws IOException {
        for (DepositSplitCase c : loadFixture().depositBalanceSplit()) {
            Money total = new Money(new BigDecimal(c.total()), CURRENCY);
            PaymentTerms terms = new PaymentTerms(
                UUID.randomUUID(), new BigDecimal(c.depositPercentage()), AnchorEvent.BL, 30);

            PaymentSplit split = terms.split(total);

            assertThat(split.deposit().amount())
                .as("%s (deposit)", c.description())
                .isEqualByComparingTo(c.expectedDeposit());
            assertThat(split.balance().amount())
                .as("%s (balance)", c.description())
                .isEqualByComparingTo(c.expectedBalance());
            assertThat(split.deposit().plus(split.balance()).amount())
                .as("%s (deposit + balance == total, always)", c.description())
                .isEqualByComparingTo(total.amount());
        }
    }

    // --- fixture shape ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Fixture(
        List<RoundingCase> unitPriceRounding,
        List<LineTotalRoundingCase> lineTotalRounding,
        List<OrderTotalCase> orderTotals,
        List<DepositSplitCase> depositBalanceSplit
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RoundingCase(String input, String expected) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LineTotalRoundingCase(String input, int scale, String expected) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrderTotalCase(
        String description, List<String> roundedLines, String correctOrderTotal) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DepositSplitCase(
        String description, String total, String depositPercentage,
        String expectedDeposit, String expectedBalance) {
    }
}
