package com.shvoy.payments.dto;

import jakarta.validation.constraints.NotBlank;

/** Accept a discrepancy as-is and force-pass the payment (Story 6.6, path c). A reason is required and audited. */
public record OverrideDiscrepancyRequest(
    @NotBlank String reason
) {
}
