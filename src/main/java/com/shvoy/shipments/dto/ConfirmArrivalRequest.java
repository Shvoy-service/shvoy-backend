package com.shvoy.shipments.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Confirm physical arrival (Story 7.6) — the arrival date and the per-SKU counts
 * actually counted at the door. The counts are compared against the provisional
 * GRN snapshot (never the PO); the comparison is the whole point, so the counts
 * are required even though "it all arrived" is the common case.
 */
public record ConfirmArrivalRequest(
    @NotNull LocalDate arrivalDate,
    @NotEmpty @Valid List<SkuQuantityRequest> arrivedLines
) {
}
