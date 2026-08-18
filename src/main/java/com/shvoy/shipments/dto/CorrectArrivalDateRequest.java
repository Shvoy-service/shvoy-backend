package com.shvoy.shipments.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

/**
 * Correct a confirmed arrival's date (Story 7.6) — re-publishes the {@code
 * ARRIVAL} anchor (6.2's re-entrancy recalculates due dates). Only the date; the
 * counts and any discrepancy stand (a count correction is a credit-lane matter).
 */
public record CorrectArrivalDateRequest(
    @NotNull LocalDate arrivalDate
) {
}
