package com.shvoy.purchaseorders.dto;

import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/**
 * A narrow, read-only view of a PO for another module to display — its
 * human reference (the {@code PO-0001} number) and its supplier. First needed
 * by Story 6.3's payment queue, which shows each payment's PO reference and
 * (via the supplier) name. Deliberately not the full {@code
 * PurchaseOrderResponse} — same minimal cross-module contract reasoning as
 * {@code SupplierSummary}/{@code SkuSummary}.
 */
@NamedInterface("purchase-orders")
public record PurchaseOrderSummary(
    UUID id,
    String poNumber,
    UUID supplierId
) {
}
