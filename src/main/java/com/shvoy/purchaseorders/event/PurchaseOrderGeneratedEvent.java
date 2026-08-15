package com.shvoy.purchaseorders.event;

import java.util.UUID;

import org.springframework.modulith.NamedInterface;

import com.shvoy.Money;

/**
 * Published when a PO is generated (Story 4.6's finalisation), the moment its
 * deposit/balance amounts are definitively locked. The {@code payments} module
 * (Story 6.1) reacts to it to create the payment obligations the split implies.
 *
 * <p>This is the codebase's <strong>first real cross-module domain event</strong>,
 * and it establishes the pattern deliberately: the {@code purchaseorders} module
 * announces a fact about itself ("a PO was generated") and knows nothing about
 * who listens — no compile-time dependency on {@code payments}, no idea payments
 * exist. Feature 7 (documents arriving → anchor dates → due-date recalculation)
 * and Notifications will publish/consume events the same way.
 *
 * <p>A deliberately "fat" event: it carries the financial figures a consumer
 * needs ({@code orderTotal}, and the {@code deposit}/{@code balance} split —
 * both null when the supplier has no payment terms configured, so no split
 * exists) rather than just the id, so a listener never has to call back into
 * this module to read them. The amounts are the 4.3 split as snapshotted at
 * generation.
 *
 * <p>Exposed via {@code @NamedInterface} so another module may depend on the
 * event type without reaching into the rest of {@code purchaseorders}.
 */
@NamedInterface("po-events")
public record PurchaseOrderGeneratedEvent(
    UUID purchaseOrderId,
    Money orderTotal,
    Money deposit,
    Money balance
) {
}
