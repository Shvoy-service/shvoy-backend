package com.shvoy.suppliers.dto;

import java.time.Instant;
import java.util.UUID;

import com.shvoy.suppliers.domain.SupplierStatus;

public record SupplierResponse(
    UUID id,
    String name,
    SupplierStatus status,
    String country,
    String contactEmail,
    Instant createdAt,
    Instant updatedAt
) {
}
