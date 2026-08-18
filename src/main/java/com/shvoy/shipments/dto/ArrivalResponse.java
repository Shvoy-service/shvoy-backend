package com.shvoy.shipments.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.shvoy.shipments.domain.ReceiptStatus;

/**
 * The arrival outcome for a consignment (Story 7.6): the resulting receipt
 * status, the arrival date, and — when the counts differed from the GRN — the
 * per-SKU discrepancy lines. {@code discrepancyLines} is empty on a clean
 * arrival.
 */
public record ArrivalResponse(
    UUID consignmentId,
    UUID purchaseOrderId,
    ReceiptStatus receiptStatus,
    LocalDate arrivalDate,
    UUID arrivalDiscrepancyId,
    List<ArrivalDiscrepancyLineResponse> discrepancyLines
) {
    public record ArrivalDiscrepancyLineResponse(
        UUID skuId,
        int expectedQuantity,
        int arrivedQuantity,
        String direction
    ) {
    }
}
