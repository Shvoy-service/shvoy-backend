package com.shvoy.shipments.dto;

import java.util.List;
import java.util.UUID;

import com.shvoy.Money;

/**
 * The PO's cumulative-receipt rollup (receipt rollup &amp; PO closure) — the one
 * derived, read-time operation summing every consignment's GRN quantities up to
 * the parent PO, valued at PO snapshot prices. {@code complete} is the closure
 * condition (received = ordered per SKU, exactly); {@code overDelivered} holds
 * closure and is surfaced for a resolver conversation (interim, pending the
 * over-delivery rule). Never cached — a GRN amendment reflects instantly.
 */
public record ReceiptRollupResponse(
    UUID purchaseOrderId,
    String currency,
    boolean complete,
    boolean overDelivered,
    Money orderedValue,
    Money receivedValue,
    Money outstandingValue,
    List<ReceiptRollupLine> lines
) {
}
