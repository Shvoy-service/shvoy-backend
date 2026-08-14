package com.shvoy.suppliers.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import com.shvoy.suppliers.domain.AnchorEvent;

/**
 * Only the deposit percentage is accepted — balance is always derived (see
 * PaymentTerms) rather than a second field a caller could send out of sync
 * with the deposit. Full representation for both set and update (PUT
 * semantics, not a partial patch), same convention as SupplierRequest.
 *
 * {@code depositPercentage}'s {@code @Digits(integer = 3, fraction = 1)}
 * caps it at 1 decimal place (33.5 valid, 33.55 rejected) — confirmed by
 * the Product Owner (Consolidation ticket); integer part allows up to 3
 * digits since the range includes 100. Same annotation-based pattern as
 * unitPriceAmount elsewhere (CreateSkuRequest/SkuPriceRequest/DiscountTierRequest),
 * just at a different scale.
 */
public record PaymentTermsRequest(
    @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 1) BigDecimal depositPercentage,
    @NotNull AnchorEvent anchorEvent,
    @NotNull @PositiveOrZero Integer daysOffset
) {
}
