package com.shvoy.suppliers.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.shvoy.UnitPrice;

public record SkuPriceResponse(
    UUID id,
    UUID skuId,
    UnitPrice unitPrice,
    LocalDate validFrom,
    LocalDate validTo,
    Instant createdAt,
    Instant updatedAt
) {
}
