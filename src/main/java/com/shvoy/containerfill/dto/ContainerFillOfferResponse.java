package com.shvoy.containerfill.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.shvoy.containerfill.domain.ContainerFillOfferStatus;

/**
 * A single offer (Story 8.1) — the full record, plus a non-blocking advisory:
 * {@code otherUndecidedOffersOnShipment} surfaces that the same container already
 * has undecided offers (capacity can be re-flagged as loading plans change; a
 * second offer is a new fact, not a correction).
 */
public record ContainerFillOfferResponse(
    UUID offerId,
    UUID shipmentId,
    String blReference,
    UUID supplierId,
    String supplierName,
    BigDecimal spareCbm,
    ContainerFillOfferStatus status,
    Instant deadline,
    Instant reminderSentAt,
    String notes,
    UUID flaggedBy,
    Instant createdAt,
    Instant updatedAt,
    int otherUndecidedOffersOnShipment
) {
}
