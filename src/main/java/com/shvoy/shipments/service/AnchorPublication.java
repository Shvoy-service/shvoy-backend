package com.shvoy.shipments.service;

import java.time.LocalDate;
import java.util.UUID;

import com.shvoy.suppliers.domain.AnchorEvent;

/**
 * An anchor date that became known while logging a shipment document, to be
 * published to the 6.2 seam once the write has committed (Story 7.2). The
 * recording service returns these; {@link ShipmentDocumentService} publishes
 * them as {@code AnchorEventDateKnownEvent}s after commit, so {@code payments}
 * reacts against durable data — exactly as {@code InvoiceService} does (6.4).
 *
 * <p>One per affected PO: a shipment-level BL/ex-factory date fans out over
 * every consignment on the shipment (trivially one here; 7.3's co-loading gets
 * the fan-out for free).
 */
record AnchorPublication(UUID purchaseOrderId, AnchorEvent anchorEvent, LocalDate anchorDate) {
}
