package com.shvoy.payments.dto;

/**
 * The Release-hold action (Story 6.8) — reason optional (releasing back to the
 * system's verdict needs less justification than overriding it). The request
 * body itself is optional at the controller.
 */
public record ReleaseHoldRequest(
    String reason
) {
}
