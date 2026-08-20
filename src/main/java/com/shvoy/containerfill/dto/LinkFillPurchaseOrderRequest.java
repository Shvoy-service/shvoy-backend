package com.shvoy.containerfill.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Wires the fill PO to an already-confirmed offer (Story 8.3) — the "decide now, raise the PO later" step. */
public record LinkFillPurchaseOrderRequest(@NotNull UUID fillPurchaseOrderId) {
}
