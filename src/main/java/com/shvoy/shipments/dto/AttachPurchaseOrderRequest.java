package com.shvoy.shipments.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Story 7.3 — attach an additional finalised PO to an existing shipment (co-loading). */
public record AttachPurchaseOrderRequest(
    @NotNull UUID purchaseOrderId
) {
}
