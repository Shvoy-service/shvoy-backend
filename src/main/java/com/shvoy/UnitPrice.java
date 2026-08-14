package com.shvoy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * A per-unit price — see docs/CONTRACT.md's Money section for why this is a
 * separate type from {@link Money} rather than the same type at a different
 * scale: procurement unit prices routinely carry 4 decimal places (e.g.
 * {@code 1.4275}), and rounding a unit price down to currency-minor-unit
 * precision before multiplying by a large order quantity would compound
 * into a real total-price error. {@code Money} stays fixed at 2dp for
 * totals/amounts; this stays fixed at 4dp for per-unit rates.
 *
 * Same wire format and rounding mode as {@code Money} otherwise — string
 * amount plus currency, HALF_EVEN — via the same {@link AmountSerializer}/
 * {@link AmountDeserializer}.
 */
public record UnitPrice(
        @JsonSerialize(using = AmountSerializer.class)
        @JsonDeserialize(using = AmountDeserializer.class)
        BigDecimal amount,
        String currency) {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;

    public UnitPrice {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Currency.getInstance(currency); // throws IllegalArgumentException for anything not a real ISO 4217 code
        amount = amount.setScale(SCALE, ROUNDING_MODE);
    }

    /**
     * The line-total composition rule (Story 4.3, docs/CONTRACT.md's Money
     * section): the raw product of this 4dp price and an integer quantity
     * is rounded exactly once, to {@code Money}'s 2dp/HALF_EVEN scale, via
     * {@code Money}'s own compact constructor — never pre-rounded to 2dp
     * before multiplying, which would compound error at volume.
     */
    public Money multiply(int quantity) {
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
    }
}
