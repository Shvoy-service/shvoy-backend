package com.shvoy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * SHVOY's monetary value type for amounts at currency-minor-unit precision
 * — totals, deposits, balances — see docs/CONTRACT.md's Money section for
 * the wire format, internal-type, and rounding decisions this encodes. Any
 * such field anywhere in the API should be a {@code Money}, not a bare
 * {@code BigDecimal}/{@code double} and never a raw JSON number on the wire
 * — this class is the single place those decisions are enforced, not a
 * convention callers have to remember on every field.
 *
 * Not for per-unit prices, which need more precision than 2dp to avoid
 * compounding rounding error when multiplied by large quantities — see
 * {@link UnitPrice}, the sibling type for that case, which shares this
 * class's wire format and rounding mode at a different scale.
 *
 * Rounding is HALF_EVEN at scale 2, applied by the compact constructor —
 * including to every arithmetic result, since {@link #plus} and
 * {@link #multiply} both build a new {@code Money} through it. That makes
 * "round each line, then sum" structural rather than a rule callers have to
 * follow: a {@code Money} is always already rounded to its canonical scale,
 * so summing two of them is exact and needs no further rounding — there is
 * no code path that sums unrounded amounts and rounds once at the end.
 *
 * One consequence worth knowing: a slightly-over-precise but otherwise
 * valid incoming amount (e.g. a client-side floating point artifact like
 * {@code "12.345000000001"}) is silently normalized to scale 2 rather than
 * rejected — only a non-numeric/malformed string fails, via Jackson
 * deserialization (surfaced as {@code VALIDATION_ERROR}, see
 * ApiExceptionHandler). Whether an incoming amount with too much precision
 * should instead be rejected outright is a call for whichever endpoint
 * first actually accepts money as request input — revisit then if silent
 * coercion turns out to be the wrong default.
 */
public record Money(
        @JsonSerialize(using = AmountSerializer.class)
        @JsonDeserialize(using = AmountDeserializer.class)
        BigDecimal amount,
        String currency) {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Currency.getInstance(currency); // throws IllegalArgumentException for anything not a real ISO 4217 code
        amount = amount.setScale(SCALE, ROUNDING_MODE);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    /**
     * Sums two already-rounded amounts — see the class Javadoc for why this
     * needs no further rounding of its own.
     */
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    /**
     * The "round each line" step itself for a unit-price * quantity style
     * computation: the raw product generally has more than 2 decimal
     * places, and the compact constructor rounds it down to Money's
     * canonical scale as this new instance is built.
     */
    public Money multiply(BigDecimal factor) {
        return new Money(amount.multiply(factor), currency);
    }

    /**
     * Subtracting two already-rounded amounts is exact at scale 2, same
     * reasoning as {@link #plus} — no further rounding is introduced.
     */
    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
