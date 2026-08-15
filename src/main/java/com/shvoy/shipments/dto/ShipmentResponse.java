package com.shvoy.shipments.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A shipment as read back for one PO (Story 7.2): the BL-level fields plus that
 * PO's own consignment. The full multi-consignment view of a co-loaded shipment
 * is 7.3's concern; here a shipment is read through the lens of the PO asked
 * for, so {@code consignment} is that PO's portion.
 */
public record ShipmentResponse(
    UUID shipmentId,
    String blReference,
    LocalDate blDate,
    LocalDate exFactoryDate,
    String blDocumentS3Key,
    ConsignmentResponse consignment,
    Instant createdAt,
    Instant updatedAt
) {
}
