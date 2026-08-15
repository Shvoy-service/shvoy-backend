package com.shvoy.suppliers.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.shvoy.suppliers.domain.AnchorEvent;
import com.shvoy.suppliers.domain.PaymentTermsType;

/** One payment-terms record as read back (supplier remodel). */
public record PaymentTermsResponse(
    UUID id,
    UUID supplierId,
    PaymentTermsType termsType,
    BigDecimal depositPct,
    AnchorEvent anchorDateType,
    int daysFromAnchor,
    Instant createdAt,
    Instant updatedAt
) {
}
