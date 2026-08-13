package com.shvoy.suppliers.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.shvoy.suppliers.domain.SkuStatus;

/**
 * SKU-level metadata only — code, description, status, carton size. Never
 * the price: price changes are new SkuPrice versions (see
 * SkuPriceRequest), not edits to anything reachable from this request.
 *
 * cartonSize is nullable — @Positive alone (no @NotNull) so a null value
 * passes validation (a SKU without a carton constraint) while a supplied
 * value must be a real positive integer.
 */
public record UpdateSkuRequest(
    @NotBlank @Size(max = 100) String code,
    @Size(max = 255) String description,
    @NotNull SkuStatus status,
    @Positive Integer cartonSize
) {
}
