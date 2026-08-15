package com.shvoy.shipments.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.shvoy.shipments.domain.ReceiptStatus;

/**
 * The provisional GRN as read back (Story 7.4) — and the shape of the Feature 7
 * → Feature 6 read contract: does a GRN exist for this PO, and what quantities
 * does it state. {@code exists} is false while the consignment is still
 * DOCUMENTS_PENDING (documents in, but not yet receipted); true once
 * provisionally receipted, with the snapshotted {@code lines} the three-way
 * match compares. 6.5 itself consumes this via the {@code
 * ProvisionalGoodsReceiptEvent} push (not a cross-module pull — that would make
 * the module graph cyclic), but the shape it reasons about is this one.
 */
public record GoodsReceiptResponse(
    UUID purchaseOrderId,
    UUID consignmentId,
    boolean exists,
    ReceiptStatus receiptStatus,
    UUID receiptedBy,
    Instant receiptedAt,
    List<GoodsReceiptLineResponse> lines
) {
}
