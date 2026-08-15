package com.shvoy.payments.dto;

import jakarta.validation.constraints.NotBlank;

/** Contest an invoice outright (Story 6.6, path d) — the case is DISPUTED, the payment stays BLOCKED. */
public record DisputeDiscrepancyRequest(
    @NotBlank String reason
) {
}
