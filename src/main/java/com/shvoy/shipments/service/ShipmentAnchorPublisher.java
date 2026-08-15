package com.shvoy.shipments.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.shvoy.payments.event.AnchorEventDateKnownEvent;

/**
 * Publishes shipment anchor dates to the 6.2 seam (Story 7.2), shared by
 * document logging and co-load attachment (7.3). Each publication is
 * <strong>best-effort</strong>: a downstream failure is logged and swallowed so
 * it can never fail — or roll back — the already-committed shipment write, the
 * same posture {@code InvoiceService#log} takes. Callers invoke this
 * <em>after</em> their write has committed, so {@code payments} reacts against
 * durable data.
 */
@Component
class ShipmentAnchorPublisher {

    private static final Logger log = LoggerFactory.getLogger(ShipmentAnchorPublisher.class);

    private final ApplicationEventPublisher eventPublisher;

    ShipmentAnchorPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    void publishAll(List<AnchorPublication> publications) {
        for (AnchorPublication publication : publications) {
            try {
                eventPublisher.publishEvent(new AnchorEventDateKnownEvent(
                    publication.purchaseOrderId(), publication.anchorEvent(), publication.anchorDate()));
            } catch (RuntimeException e) {
                log.warn("Anchor-date publish failed for PO {} ({} = {}) — shipment write remains committed",
                    publication.purchaseOrderId(), publication.anchorEvent(), publication.anchorDate(), e);
            }
        }
    }
}
