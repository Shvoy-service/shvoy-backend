package com.shvoy.payments.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.shvoy.payments.domain.CreditCause;

/**
 * Logs a credit against a PO (Story 6.7). Amount is a positive 2dp figure with
 * a real ISO 4217 currency (pattern-checked here, ISO-validated in the service).
 * {@code causeDetail} is required when {@code cause} is {@code OTHER} — a
 * service-level cross-field check. {@code ncrReference} and {@code
 * targetInvoiceId} are optional (the latter usually null: the target invoice is
 * a future one).
 */
public record LogCreditRequest(
    @NotNull UUID purchaseOrderId,
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal amount,
    @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 currency code") String currency,
    @NotNull CreditCause cause,
    String causeDetail,
    String ncrReference,
    UUID targetInvoiceId
) {
}
