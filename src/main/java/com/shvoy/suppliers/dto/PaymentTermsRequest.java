package com.shvoy.suppliers.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import com.shvoy.suppliers.domain.AnchorEvent;

/**
 * Only the deposit percentage is accepted — balance is always derived (see
 * PaymentTerms) rather than a second field a caller could send out of sync
 * with the deposit. Full representation for both set and update (PUT
 * semantics, not a partial patch), same convention as SupplierRequest.
 */
public record PaymentTermsRequest(
    @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal depositPercentage,
    @NotNull AnchorEvent anchorEvent,
    @NotNull @PositiveOrZero Integer daysOffset
) {
}
