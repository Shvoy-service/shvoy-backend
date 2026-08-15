package com.shvoy.payments.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.shvoy.payments.domain.CreditCause;

/**
 * Logs a credit in the ledger from a discrepancy case (Story 6.6, path b). Same
 * shape as {@link LogCreditRequest} minus the PO (taken from the case) and the
 * target invoice — the resolver agrees a deduction, and the case links to the
 * entry.
 */
public record LogCaseCreditRequest(
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal amount,
    @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 currency code") String currency,
    @NotNull CreditCause cause,
    String causeDetail,
    String ncrReference
) {
}
