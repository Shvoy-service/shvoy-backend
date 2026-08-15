package com.shvoy.shipments.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.shvoy.shipments.domain.GrnProvenance;
import com.shvoy.shipments.domain.ReceiptStatus;

/**
 * The provisional GRN as read back (Story 7.4) — and the shape of the Feature 7
 * → Feature 6 read contract: does a GRN exist for this PO, and what quantities
 * does it state. {@code exists} is false while the consignment is still
 * DOCUMENTS_PENDING (documents in, but not yet receipted); true once
 * provisionally receipted, with the snapshotted {@code lines} the three-way
 * match compares. {@code provenance}/{@code qcFailed} carry the inspection
 * provenance (7.4 revised) — {@code qcFailed} is the flagged, discrepancy-visible
 * state that a failed inspection produces, and it does <em>not</em> block the
 * match. 6.5 consumes the quantities via the {@code ProvisionalGoodsReceiptEvent}
 * push (a cross-module pull would make the module graph cyclic); this is the
 * shape it reasons about.
 */
public record GoodsReceiptResponse(
    UUID purchaseOrderId,
    UUID consignmentId,
    boolean exists,
    ReceiptStatus receiptStatus,
    GrnProvenance provenance,
    boolean qcFailed,
    UUID receiptedBy,
    Instant receiptedAt,
    List<GoodsReceiptLineResponse> lines
) {
}
