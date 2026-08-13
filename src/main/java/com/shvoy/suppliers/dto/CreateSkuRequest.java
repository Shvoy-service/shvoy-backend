package com.shvoy.suppliers.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Creates a SKU together with its first SkuPrice version in one call — a
 * SKU without any price isn't a meaningful state to leave one in. Further
 * prices are added via SkuPriceRequest against POST .../prices, never by
 * calling this again.
 */
public record CreateSkuRequest(
    @NotBlank @Size(max = 100) String code,
    @Size(max = 255) String description,
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal unitPriceAmount,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 currency code") String currency,
    @NotNull LocalDate validFrom,
    LocalDate validTo
) {
}
