package com.shvoy;

import java.io.IOException;
import java.math.BigDecimal;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

/**
 * Writes a monetary amount as a JSON string (e.g. {@code "1234.56"}), never
 * a bare JSON number — see Money's Javadoc. Shared by every amount-plus-
 * currency type (Money, UnitPrice) since the wire rule is the same
 * regardless of scale. toPlainString rather than toString: BigDecimal.
 * toString can fall back to scientific notation for very small/large
 * values, which toPlainString never does.
 */
class AmountSerializer extends JsonSerializer<BigDecimal> {

    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeString(value.toPlainString());
    }
}
