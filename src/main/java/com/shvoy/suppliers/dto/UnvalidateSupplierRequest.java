package com.shvoy.suppliers.dto;

import jakarta.validation.constraints.NotBlank;

/** Revert a supplier's validation (supplier remodel) — a reason is required and audited. */
public record UnvalidateSupplierRequest(
    @NotBlank String reason
) {
}
