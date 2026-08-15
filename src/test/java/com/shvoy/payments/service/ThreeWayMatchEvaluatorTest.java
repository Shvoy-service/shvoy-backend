package com.shvoy.payments.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.shvoy.Money;
import com.shvoy.payments.service.ThreeWayMatchEvaluator.MatchInputs;
import com.shvoy.payments.service.ThreeWayMatchEvaluator.MatchInputs.PiLine;
import com.shvoy.payments.service.ThreeWayMatchEvaluator.MatchVerdict;

/**
 * The three-way match rule (Story 6.5), pure. Pins the two things a quick
 * implementation would quietly get wrong: the reference basis (b) — PI prices ×
 * <em>GRN</em> quantities — and exact 2dp money equality (a penny is a fail).
 */
class ThreeWayMatchEvaluatorTest {

    private static final UUID SKU = UUID.randomUUID();

    @Test
    void cleanMatchPasses() {
        MatchVerdict v = ThreeWayMatchEvaluator.evaluate(inputs(10, 10, 10, "2.0000", "20.00", null, false));
        assertThat(v.passed()).isTrue();
        assertThat(v.detail()).isNull();
    }

    @Test
    void shortShipmentWithFullInvoiceFails_theBasisBFailureMode() {
        // Ordered/confirmed 10, only 8 received, but invoiced for the full 10 × 2.00 = 20.00.
        MatchVerdict v = ThreeWayMatchEvaluator.evaluate(inputs(10, 10, 8, "2.0000", "20.00", null, false));
        assertThat(v.passed()).isFalse();
        // Both the quantity chain and the amount (expected 8 × 2.00 = 16.00) catch it.
        assertThat(v.detail()).contains("Quantity").contains("GRN=8");
        assertThat(v.detail()).contains("expected USD 16.00").contains("invoice is USD 20.00");
    }

    @Test
    void aPennyOverFails_exactEqualityNoTolerance() {
        MatchVerdict v = ThreeWayMatchEvaluator.evaluate(inputs(10, 10, 10, "2.0000", "20.01", null, false));
        assertThat(v.passed()).isFalse();
        assertThat(v.detail()).contains("Amount").contains("expected USD 20.00");
    }

    @Test
    void agreedCreditIsDeductedAndPasses() {
        // Goods 20.00, a validated credit of 5.00 → expected 15.00, invoiced 15.00.
        MatchVerdict v = ThreeWayMatchEvaluator.evaluate(inputs(10, 10, 10, "2.0000", "15.00", money("5.00"), true));
        assertThat(v.passed()).isTrue();
    }

    @Test
    void unagreedClaimedCreditFails() {
        MatchVerdict v = ThreeWayMatchEvaluator.evaluate(
            new MatchInputs(Map.of(SKU, 10), "USD", List.of(new PiLine(SKU, new BigDecimal("2.0000"), 10)),
                Map.of(SKU, 10), money("15.00"), money("5.00"), false, "NO_OPEN_CREDIT"));
        assertThat(v.passed()).isFalse();
        assertThat(v.detail()).contains("Claimed credit").contains("NO_OPEN_CREDIT");
    }

    @Test
    void currencyMismatchFails() {
        MatchVerdict v = ThreeWayMatchEvaluator.evaluate(
            new MatchInputs(Map.of(SKU, 10), "USD", List.of(new PiLine(SKU, new BigDecimal("2.0000"), 10)),
                Map.of(SKU, 10), money("GBP", "20.00"), null, false, null));
        assertThat(v.passed()).isFalse();
        assertThat(v.detail()).contains("Currency");
    }

    @Test
    void lineValueIsRoundedOnce_notPerUnit() {
        // 2.3333 × 3 = 6.9999 → 7.00 (HALF_EVEN, rounded once), invoiced 7.00.
        MatchVerdict v = ThreeWayMatchEvaluator.evaluate(inputs(3, 3, 3, "2.3333", "7.00", null, false));
        assertThat(v.passed()).isTrue();
    }

    private static MatchInputs inputs(int poQty, int piQty, int grnQty, String piPrice, String invoice,
            Money claimedCredit, boolean creditValid) {
        return new MatchInputs(
            Map.of(SKU, poQty),
            "USD",
            List.of(new PiLine(SKU, new BigDecimal(piPrice), piQty)),
            Map.of(SKU, grnQty),
            money(invoice),
            claimedCredit,
            creditValid,
            null);
    }

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount), "USD");
    }

    private static Money money(String currency, String amount) {
        return new Money(new BigDecimal(amount), currency);
    }
}
