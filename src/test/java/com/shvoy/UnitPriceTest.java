package com.shvoy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class UnitPriceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void constructionRoundsToFourDecimalPlacesHalfEven() {
        // 0.00125 ties between 0.0012 and 0.0013 — HALF_EVEN picks 0.0012 (even).
        assertThat(new UnitPrice(new BigDecimal("0.00125"), "USD").amount()).isEqualByComparingTo("0.0012");
    }

    @Test
    void keepsPrecisionThatMoneyWouldHaveTruncated() {
        // The exact case this type exists for: a unit price with 4 real
        // decimal places, which Money's 2dp scale would have destroyed.
        UnitPrice price = new UnitPrice(new BigDecimal("1.4275"), "GBP");

        assertThat(price.amount()).isEqualByComparingTo("1.4275");
    }

    @Test
    void rejectsAnInvalidCurrencyCode() {
        assertThatThrownBy(() -> new UnitPrice(BigDecimal.TEN, "NOT_A_CURRENCY"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serialisesAmountAsAStringNeverABareJsonNumber() throws Exception {
        String json = objectMapper.writeValueAsString(new UnitPrice(new BigDecimal("1.4275"), "GBP"));

        assertThat(json).isEqualTo("{\"amount\":\"1.4275\",\"currency\":\"GBP\"}");
    }

    @Test
    void deserialisesAndRoundTripsThroughTheStringFormat() throws Exception {
        UnitPrice price = objectMapper.readValue("{\"amount\":\"1.4275\",\"currency\":\"GBP\"}", UnitPrice.class);

        assertThat(price).isEqualTo(new UnitPrice(new BigDecimal("1.4275"), "GBP"));
    }
}
