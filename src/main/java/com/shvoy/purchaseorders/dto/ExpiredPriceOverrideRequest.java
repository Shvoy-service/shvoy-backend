package com.shvoy.purchaseorders.dto;

import java.util.List;

/**
 * A caller's attempt to override Story 4.5's expired-price finalisation
 * gate — a non-blank {@code reason} plus a manual price for every line the
 * gate found blocked (option (a) of the still-open "what price does an
 * overridden line carry" question — see docs/CONTRACT.md). Missing either
 * leaves the block standing; {@code PurchaseOrderFinalisationGateService}
 * enforces both by hand rather than via Bean Validation annotations, since
 * no controller binds this from a request body yet — 4.6's future finalise
 * endpoint is expected to accept this shape (nested in its own request)
 * once it exists, at which point {@code @Valid}/{@code @NotBlank} etc. can
 * be added the same way every other request DTO in this codebase does it.
 */
public record ExpiredPriceOverrideRequest(
    String reason,
    List<LineOverridePrice> lines
) {
}
