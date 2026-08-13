package com.shvoy.suppliers.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.shvoy.suppliers.domain.AnchorEvent;

public record PaymentTermsResponse(
    UUID supplierId,
    BigDecimal depositPercentage,
    BigDecimal balancePercentage,
    AnchorEvent anchorEvent,
    int daysOffset,
    Instant createdAt,
    Instant updatedAt
) {
}
