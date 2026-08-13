package com.shvoy.suppliers.dto;

import java.time.Instant;
import java.util.UUID;

import com.shvoy.suppliers.domain.SkuStatus;

public record SkuResponse(
    UUID id,
    UUID supplierId,
    String code,
    String description,
    SkuStatus status,
    Integer cartonSize,
    Instant createdAt,
    Instant updatedAt
) {
}
