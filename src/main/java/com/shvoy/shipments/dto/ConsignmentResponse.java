package com.shvoy.shipments.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.shvoy.shipments.domain.ReceiptStatus;

/**
 * A PO's portion of a shipment, as read back (Story 7.2) — its own packing list
 * and inspection report (structured fields + stored-file references) and its
 * receipt status. ETD/arrival fields exist on the entity but belong to 7.5/7.6,
 * so they're not surfaced here yet.
 */
public record ConsignmentResponse(
    UUID consignmentId,
    UUID purchaseOrderId,
    String packingListReference,
    LocalDate packingListDate,
    String packingListS3Key,
    String inspectionReportReference,
    LocalDate inspectionReportDate,
    String inspectionReportOutcome,
    String inspectionReportS3Key,
    ReceiptStatus receiptStatus,
    boolean receiptEligible,
    Instant createdAt,
    Instant updatedAt
) {
}
