package com.shvoy.reconciliation.dto;

import java.time.Instant;
import java.util.UUID;

import com.shvoy.UnitPrice;

public record ProformaInvoiceLineResponse(
    UUID id,
    UUID skuId,
    int lineNumber,
    UnitPrice confirmedUnitPrice,
    int confirmedQuantity,
    Instant createdAt
) {
}
