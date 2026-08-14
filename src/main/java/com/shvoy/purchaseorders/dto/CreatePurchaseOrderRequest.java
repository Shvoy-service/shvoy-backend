package com.shvoy.purchaseorders.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record CreatePurchaseOrderRequest(
    @NotNull UUID supplierId
) {
}
