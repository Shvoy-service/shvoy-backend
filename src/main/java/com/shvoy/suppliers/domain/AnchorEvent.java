package com.shvoy.suppliers.domain;

import org.springframework.modulith.NamedInterface;

/**
 * The event a supplier's payment due date is measured from — see
 * PaymentTerms. The balance's due date is this event's date plus the terms'
 * signed days offset; Feature 7 supplies the actual dates.
 *
 * Exposed via {@code @NamedInterface} (Story 6.2) because it's the shared
 * anchor-event vocabulary: {@code payments} snapshots one onto a balance
 * payment and matches on it when an anchor date becomes known, and Feature 7
 * will name one when it logs a shipment document.
 */
@NamedInterface("payment-terms")
public enum AnchorEvent {
    BL,
    INVOICE,
    ARRIVAL,
    EX_FACTORY
}
