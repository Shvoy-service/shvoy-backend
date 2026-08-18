package com.shvoy.shipments.dto;

import java.util.UUID;

import com.shvoy.Money;

/**
 * One SKU's line in the receipt rollup (receipt rollup &amp; PO closure): ordered
 * vs cumulative received (summed across all the PO's consignments' GRNs), the
 * received value at PO snapshot prices, and whether it's over-delivered. Per-SKU
 * granularity is the requirement — {@code LINES} matching and over-delivery are
 * line-grained, and closure is per-SKU exact (net-zero mismatches don't close).
 */
public record ReceiptRollupLine(
    UUID skuId,
    int orderedQuantity,
    int receivedQuantity,
    boolean overDelivered,
    Money receivedValue
) {
}
