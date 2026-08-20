package com.shvoy.containerfill.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/**
 * Records a supplier's spare-capacity offer (Story 8.1). CBM is fractional (2.5
 * CBM is normal) — 2dp is ample. The supplier who flagged it is captured
 * explicitly (a co-loaded container has several). Notes are optional free text
 * ("can fit 2 more pallets if confirmed by Friday").
 */
public record FlagContainerFillOfferRequest(
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 2) BigDecimal spareCbm,
    @NotNull UUID supplierId,
    String notes
) {
}
