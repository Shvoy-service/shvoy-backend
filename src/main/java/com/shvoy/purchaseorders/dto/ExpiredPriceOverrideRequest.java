package com.shvoy.purchaseorders.dto;

import java.util.List;

/**
 * A caller's attempt to override Story 4.5's expired-price finalisation
 * gate — a non-blank {@code reason} plus a manual price for every line the
 * gate found blocked (option (a) of the still-open "what price does an
 * overridden line carry" question — see docs/CONTRACT.md). Missing either
 * leaves the block standing. Nested inside {@link GeneratePurchaseOrderRequest}
 * (Story 4.6's {@code POST .../generate} endpoint).
 *
 * Deliberately no Bean Validation annotations — see that class's Javadoc
 * for why an incomplete override here means {@code PO_HAS_EXPIRED_PRICES}/409
 * (enforced by hand in {@code PurchaseOrderFinalisationGateService}), not a
 * blanket {@code VALIDATION_ERROR}/400.
 */
public record ExpiredPriceOverrideRequest(
    String reason,
    List<LineOverridePrice> lines
) {
}
