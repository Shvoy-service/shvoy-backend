package com.shvoy.reconciliation.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/**
 * Sets the account's reconciliation tolerance % (Story 5.4). A percentage in
 * the same units as a variance % (e.g. {@code 2.00} = 2%), 2dp to match the
 * variance scale, strictly positive (a zero tolerance would route even exact
 * matches, since the boundary is exclusive), capped at 100%.
 */
public record UpdateToleranceSettingRequest(
    @NotNull
    @DecimalMin(value = "0", inclusive = false)
    @DecimalMax("100")
    @Digits(integer = 3, fraction = 2)
    BigDecimal tolerancePercentage
) {
}
