package com.shvoy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class MoneyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void constructionRoundsHalfEvenNotHalfUp() {
        // 0.125 is an exact tie between 0.12 and 0.13 — HALF_EVEN picks 0.12
        // (even); HALF_UP would pick 0.13. This is the case that actually
        // distinguishes the two modes, not just "some rounding happened".
        assertThat(new Money(new BigDecimal("0.125"), "USD").amount()).isEqualByComparingTo("0.12");
        // 0.135 ties between 0.13 and 0.14 — HALF_EVEN picks 0.14 (even).
        assertThat(new Money(new BigDecimal("0.135"), "USD").amount()).isEqualByComparingTo("0.14");
    }

    @Test
    void roundsEachLineThenSumsRatherThanSummingRawAndRoundingOnce() {
        // Each line's raw product is exactly 0.125 before rounding.
        Money line1 = new Money(new BigDecimal("0.5"), "USD").multiply(new BigDecimal("0.25"));
        Money line2 = new Money(new BigDecimal("0.5"), "USD").multiply(new BigDecimal("0.25"));
        assertThat(line1.amount()).isEqualByComparingTo("0.12");

        Money total = line1.plus(line2);

        // Round-each-line-then-sum: 0.12 + 0.12 = 0.24.
        // Round-only-the-final-sum would instead sum the raw 0.125 + 0.125
        // = 0.250 and round once, giving 0.25 — a different answer. This
        // confirms which of the two policies is actually implemented.
        assertThat(total.amount()).isEqualByComparingTo("0.24");
    }

    @Test
    void plusRejectsMismatchedCurrencies() {
        Money usd = new Money(BigDecimal.TEN, "USD");
        Money gbp = new Money(BigDecimal.TEN, "GBP");

        assertThatThrownBy(() -> usd.plus(gbp)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnInvalidCurrencyCode() {
        assertThatThrownBy(() -> new Money(BigDecimal.TEN, "NOT_A_CURRENCY"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serialisesAmountAsAStringNeverABareJsonNumber() throws Exception {
        String json = objectMapper.writeValueAsString(new Money(new BigDecimal("1234.5"), "USD"));

        assertThat(json).isEqualTo("{\"amount\":\"1234.50\",\"currency\":\"USD\"}");
    }

    @Test
    void deserialisesAndRoundTripsThroughTheStringFormat() throws Exception {
        Money money = objectMapper.readValue("{\"amount\":\"1234.56\",\"currency\":\"USD\"}", Money.class);

        assertThat(money).isEqualTo(new Money(new BigDecimal("1234.56"), "USD"));
    }
}
