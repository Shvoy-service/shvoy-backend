package com.shvoy.purchaseorders.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Shared by add and edit (full-replace PUT semantics, same convention as
 * {@code UpdateSkuRequest}) — both a new line and an edited line are fully
 * specified by "which sku, how many."
 */
public record PurchaseOrderLineRequest(
    @NotNull UUID skuId,
    @Positive int quantity
) {
}
