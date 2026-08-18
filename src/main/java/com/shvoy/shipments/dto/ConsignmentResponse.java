package com.shvoy.shipments.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.shvoy.shipments.domain.ReceiptStatus;

/**
 * A PO's portion of a shipment, as read back (Story 7.2) — its own packing list
 * and inspection report (structured fields + stored-file references) and its
 * receipt status. {@code confirmedEtd} is the supplier's confirmed departure
 * (7.5); the signed delta vs the PO's requested ETD and the revision history are
 * served by the dedicated ETD read ({@code GET .../shipment/etd}), composed onto
 * this view by the frontend.
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
    LocalDate confirmedEtd,
    Instant createdAt,
    Instant updatedAt
) {
}
