package com.shvoy.shipments.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A shipment as read back for one PO (Story 7.2): the BL-level fields plus that
 * PO's own consignment, read through the lens of the PO asked for. Story 7.3
 * adds the co-load context: {@code coLoaded} is true when this BL carries other
 * (non-detached) POs too, and {@code coLoadedWithPurchaseOrderCount} is how many
 * — surfaced so the response shape never assumes a single consignment. The full
 * per-consignment listing lives at {@code GET /api/shipments/{id}/consignments}.
 */
public record ShipmentResponse(
    UUID shipmentId,
    String blReference,
    LocalDate blDate,
    LocalDate exFactoryDate,
    String blDocumentS3Key,
    ConsignmentResponse consignment,
    boolean coLoaded,
    int coLoadedWithPurchaseOrderCount,
    Instant createdAt,
    Instant updatedAt
) {
}
