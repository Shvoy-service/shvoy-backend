package com.shvoy.suppliers.dto;

import java.time.Instant;
import java.util.UUID;

import com.shvoy.UnitPrice;

public record DiscountTierResponse(UUID id, int quantityThreshold, UnitPrice unitPrice, Instant createdAt) {
}
