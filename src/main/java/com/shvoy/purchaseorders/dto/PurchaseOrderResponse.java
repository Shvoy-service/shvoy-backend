package com.shvoy.purchaseorders.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.shvoy.Money;
import com.shvoy.purchaseorders.domain.PurchaseOrderStatus;

/**
 * Every mutation (4.4's create/add-line/edit-line/remove-line/set-etd)
 * returns this full representation — lines and totals included — rather
 * than a bare ack, so a caller never needs a follow-up GET to see what its
 * own write actually produced (same reasoning as SkuWithPriceResponse).
 */
public record PurchaseOrderResponse(
    UUID id,
    UUID supplierId,
    String poNumber,
    PurchaseOrderStatus status,
    LocalDate requestedEtd,
    UUID createdBy,
    Money orderTotal,
    Money deposit,
    Money balance,
    List<PurchaseOrderLineResponse> lines,
    Instant createdAt,
    Instant updatedAt
) {
}
