package com.shvoy.suppliers.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import com.shvoy.suppliers.domain.AnchorEvent;
import com.shvoy.suppliers.domain.PaymentTermsType;

/**
 * Set/replace a supplier's terms (supplier remodel). Full representation (PUT).
 * {@code depositPct} is nullable and only valid for {@code DEPOSIT_BALANCE}
 * (0 &lt; pct &lt; 100, 1dp — the confirmed precision rule carries over);
 * type-consistency across the fields is enforced in the service with a stable
 * code. {@code anchorDateType} now includes {@code STATEMENT_DATE} (coherent
 * only for {@code ROLLING}). {@code daysFromAnchor} is signed (± the anchor).
 */
public record PaymentTermsRequest(
    @NotNull PaymentTermsType termsType,
    @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 1) BigDecimal depositPct,
    @NotNull AnchorEvent anchorDateType,
    @NotNull @Min(-365) @Max(365) Integer daysFromAnchor
) {
}
