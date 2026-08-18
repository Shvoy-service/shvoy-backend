package com.shvoy.shipments.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The ETD view for a PO (Story 7.5) — the confirmed ETD against the PO's
 * requested ETD, the derived delta, and the full revision history. A standalone
 * read the frontend composes into the consignment, shipment, and PO screens
 * (the same pattern as the running position / receipt rollup / match results).
 *
 * <p>{@code deltaDays} is signed — positive means the confirmed ETD is
 * <em>later</em> than requested (the slip), negative earlier. Derived at read
 * time from the two source dates, never stored (either can change). Null (with
 * {@code awaitingConfirmation} true) when no ETD has been confirmed yet — shown
 * honestly, not as a fake zero.
 */
public record EtdResponse(
    UUID purchaseOrderId,
    LocalDate requestedEtd,
    LocalDate confirmedEtd,
    Integer deltaDays,
    boolean awaitingConfirmation,
    List<EtdRevisionResponse> history
) {
    /** One history entry, newest first. */
    public record EtdRevisionResponse(
        LocalDate confirmedEtd,
        String reason,
        UUID changedBy,
        Instant changedAt
    ) {
    }
}
