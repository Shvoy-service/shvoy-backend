package com.shvoy.payments.event;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;

import com.shvoy.suppliers.domain.AnchorEvent;

/**
 * "An anchor event's date became known for a PO" — the inbound seam the
 * payments module offers so a balance's due date can be calculated (Story
 * 6.2). Feature 7 (shipment documents) will publish this when it logs a BL /
 * invoice / ex-factory / arrival date; {@code payments} reacts and sets the
 * affected due dates. Until then no one publishes it, and balance due dates
 * stay null.
 *
 * <p><strong>Re-entrant by design:</strong> the date can be published again
 * (a confirmed ETA shifts, an arrival corrects) — the listener recalculates
 * and audits the change. This is the foundation of the Phase 2 "automatic
 * recalculation as ETA shifts" item.
 *
 * <p>Defined here, on the consuming side, as the payments module's inbound
 * contract (a {@code @NamedInterface} the future publisher depends on) —
 * mirroring how {@code PurchaseOrderGeneratedEvent} is owned by its publisher;
 * either direction is fine, the point is the modules communicate only through
 * the event, never a direct call.
 */
@NamedInterface("payment-events")
public record AnchorEventDateKnownEvent(
    UUID purchaseOrderId,
    AnchorEvent anchorEvent,
    LocalDate anchorDate
) {
}
