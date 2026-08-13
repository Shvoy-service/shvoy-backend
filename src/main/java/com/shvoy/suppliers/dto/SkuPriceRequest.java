package com.shvoy.suppliers.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Adds a new price version to an existing SKU. See Sku & price model
 * (docs/CONTRACT.md) for the supersession rule this feeds into.
 */
public record SkuPriceRequest(
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal unitPriceAmount,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 currency code") String currency,
    @NotNull LocalDate validFrom,
    LocalDate validTo
) {
}
