package com.shvoy.shipments.dto;

import java.util.UUID;

import com.shvoy.shipments.domain.ReceiptStatus;

/**
 * One consignment in a shipment's co-load listing (Story 7.3) — the "linked POs
 * sharing this BL" view Screen 5's toggle implies: each portion's PO and
 * supplier, its document status, and its receipt eligibility. {@code
 * receiptEligible} is the per-consignment packing-list rule; it reflects only
 * this portion's own packing list, never a sibling's.
 */
public record ConsignmentSummaryResponse(
    UUID consignmentId,
    UUID purchaseOrderId,
    String poNumber,
    UUID supplierId,
    String supplierName,
    boolean packingListLogged,
    boolean inspectionReportLogged,
    ReceiptStatus receiptStatus,
    boolean receiptEligible
) {
}
