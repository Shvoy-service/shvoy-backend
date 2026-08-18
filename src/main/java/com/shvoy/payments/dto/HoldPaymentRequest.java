package com.shvoy.payments.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The Hold action (Story 6.8) — a <strong>mandatory reason</strong>. Hold is
 * Finance's brake on a payment the system considers clean, so the human's
 * override of "ready" deserves a recorded why.
 */
public record HoldPaymentRequest(
    @NotBlank String reason
) {
}
