package com.shvoy.containerfill.dto;

import java.util.UUID;

/**
 * Confirms a container-fill offer (Story 8.3). {@code fillPurchaseOrderId} is
 * <strong>optional</strong> — the offer is the decision record; the fill PO is the
 * commercial instrument, raised through the standard Feature-4 flow and linkable
 * now or later (the offer doesn't gate on the PO's paperwork). No existing PO is
 * ever mutated; the fill is a new order that rides the container via the 7.3 co-load.
 */
public record ConfirmContainerFillOfferRequest(UUID fillPurchaseOrderId) {
}
