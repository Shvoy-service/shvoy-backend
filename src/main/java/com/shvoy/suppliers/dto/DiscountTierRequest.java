package com.shvoy.suppliers.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * One tier of a SetDiscountTiersRequest — no currency field, since a
 * tier's currency always comes from its parent SkuPrice (see DiscountTier).
 */
public record DiscountTierRequest(
    @NotNull @Positive Integer quantityThreshold,
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal unitPriceAmount
) {
}
