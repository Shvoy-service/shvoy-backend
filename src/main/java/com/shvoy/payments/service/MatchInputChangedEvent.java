package com.shvoy.payments.service;

import java.util.UUID;

/**
 * A payments-internal signal that a leg of a PO's three-way match changed — an
 * invoice was logged/superseded (6.4) or a credit was logged (6.7) — so the
 * match should re-evaluate (Story 6.5). Intra-module and package-private on
 * purpose; the cross-module legs (GRN, confirmed PI, PO generation) arrive as
 * their own module-owned events. Using an event rather than a direct call lets
 * the trigger listener re-evaluate uniformly, after the writer's transaction
 * commits.
 */
record MatchInputChangedEvent(UUID purchaseOrderId) {
}
