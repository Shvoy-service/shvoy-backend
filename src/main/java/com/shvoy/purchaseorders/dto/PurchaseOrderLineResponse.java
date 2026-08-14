package com.shvoy.purchaseorders.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.shvoy.Money;
import com.shvoy.UnitPrice;

public record PurchaseOrderLineResponse(
    UUID id,
    UUID skuId,
    int lineNumber,
    int quantity,
    UnitPrice unitPrice,
    Integer appliedTierThreshold,
    Money lineTotal,
    Boolean priceFound,
    LocalDate pricedAsOfDate,
    Boolean cartonValid,
    Integer adjustedQuantity,
    Instant createdAt,
    Instant updatedAt
) {
}
