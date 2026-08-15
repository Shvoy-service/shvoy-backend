package com.shvoy.payments.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** One line a {@code LINES}-coverage invoice claims: the PO's SKU and the claimed quantity (invoice remodel). */
public record InvoiceCoveredLineRequest(
    @NotNull UUID skuId,
    @Positive int quantity
) {
}
