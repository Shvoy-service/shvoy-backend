package com.shvoy.shipments.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * A per-SKU quantity line (Story 7.4) — used both for a packing list's itemised
 * quantities and for a deliberate provisional-GRN amendment. Manually entered at
 * MVP; the AI extraction layer feeds the same shape later.
 */
public record SkuQuantityRequest(
    @NotNull UUID skuId,
    @Positive int quantity
) {
}
