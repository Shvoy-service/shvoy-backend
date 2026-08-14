package com.shvoy.purchaseorders.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

/**
 * {@code requestedEtd} is required here (not nullable) — clearing an
 * already-set ETD isn't a use case this story's acceptance criteria call
 * for, only setting/updating it. Past-date rejection happens in
 * PurchaseOrderService, not via a Bean Validation annotation, since "past"
 * means relative to today, not a fixed bound.
 */
public record UpdateRequestedEtdRequest(
    @NotNull LocalDate requestedEtd
) {
}
