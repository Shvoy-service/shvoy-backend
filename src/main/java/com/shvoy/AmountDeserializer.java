package com.shvoy;

import java.io.IOException;
import java.math.BigDecimal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/**
 * Reads an amount-plus-currency type's amount from a JSON string — shared
 * by Money and UnitPrice. A non-numeric string throws NumberFormatException,
 * which Jackson wraps and Spring surfaces as HttpMessageNotReadableException
 * — already mapped to VALIDATION_ERROR by ApiExceptionHandler, so no
 * separate error handling is needed here.
 */
class AmountDeserializer extends JsonDeserializer<BigDecimal> {

    @Override
    public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return new BigDecimal(p.getText());
    }
}
