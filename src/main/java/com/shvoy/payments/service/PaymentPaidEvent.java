package com.shvoy.payments.service;

import java.util.UUID;

/**
 * A payment was recorded as paid (Story 6.8) — published on the Pay action. The
 * running position's %-paid is derived at read time so it needs no listener; the
 * event exists for the future statement view (and any later ledger consumer) to
 * react to a settlement being recorded, following the same internal-event pattern
 * as {@code MatchInputChangedEvent}. No consumer yet — publishing it is a harmless
 * no-op that models the seam.
 */
record PaymentPaidEvent(UUID purchaseOrderId, UUID paymentId) {
}
