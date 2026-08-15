package com.shvoy.payments.event;

import java.util.List;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/**
 * "A consignment was provisionally receipted for a PO, and here are the received
 * per-SKU quantities" — the Feature 7 → Feature 6 seam (Story 7.4). This is the
 * record the three-way match (6.5) runs against: payment readiness keys off
 * <em>documented</em> receipt, not physical landing.
 *
 * <p><strong>Push, not pull — deliberately.</strong> {@code shipments} already
 * depends on {@code payments} (it publishes {@link AnchorEventDateKnownEvent}),
 * so a {@code payments → shipments} pull would make the module graph cyclic
 * (rejected by {@code ModularityTests}). So, exactly like the anchor seam, this
 * event is <strong>owned by the consuming side</strong> ({@code payments}) as
 * its inbound contract and <strong>published by</strong> {@code shipments};
 * 6.5 will react to it (re-evaluating a match blocked on a missing GRN) with the
 * quantities carried in the payload, never calling back into {@code shipments}.
 *
 * <p>A <strong>fat event</strong>: it carries the received quantities so the
 * listener needs no follow-up read. Re-published on a GRN amendment so a revised
 * quantity re-drives the match — the same re-entrant posture as the anchor seam.
 * Until 6.5 exists no one listens, and publishing it is a harmless no-op.
 */
@NamedInterface("payment-events")
public record ProvisionalGoodsReceiptEvent(
    UUID purchaseOrderId,
    UUID consignmentId,
    List<ProvisionalGoodsReceiptLine> receivedLines
) {
}
