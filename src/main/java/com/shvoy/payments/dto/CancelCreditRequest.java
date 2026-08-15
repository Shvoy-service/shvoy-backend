package com.shvoy.payments.dto;

import jakarta.validation.constraints.NotBlank;

/** Cancels/writes off an open credit (Story 6.7) — the reason is required and audited. */
public record CancelCreditRequest(
    @NotBlank String reason
) {
}
