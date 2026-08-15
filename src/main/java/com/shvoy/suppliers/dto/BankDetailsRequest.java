package com.shvoy.suppliers.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Set a supplier's bank details (supplier remodel). Sensitive payment data —
 * changing them on a VALIDATED supplier reverts it to PENDING (the invoice-fraud
 * control). FINANCE/ADMIN only.
 */
public record BankDetailsRequest(
    @NotBlank String accountName,
    @NotBlank String accountNumber,
    @NotBlank String sortCode
) {
}
