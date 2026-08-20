package com.shvoy.containerfill.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.shvoy.containerfill.domain.ContainerFillOfferStatus;

/** A row in the offers list (Story 8.1) — shipment + supplier context, capacity, deadline, status. */
public record ContainerFillOfferSummary(
    UUID offerId,
    UUID shipmentId,
    String blReference,
    UUID supplierId,
    String supplierName,
    BigDecimal spareCbm,
    ContainerFillOfferStatus status,
    Instant deadline,
    Instant createdAt
) {
}
