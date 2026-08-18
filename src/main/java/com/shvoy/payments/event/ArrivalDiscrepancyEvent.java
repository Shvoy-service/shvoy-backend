package com.shvoy.payments.event;

import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/**
 * "A physical arrival's counts differed from the provisional GRN" (Story 7.6) —
 * the seam that surfaces an arrival discrepancy into the resolver / credit lane.
 * <strong>Owned by the consuming side ({@code payments})</strong> and published
 * by {@code shipments}, exactly like {@link QcFailureEvent} and {@link
 * ProvisionalGoodsReceiptEvent} — {@code shipments} already depends on {@code
 * payments}, so a payments-owned inbound event keeps the module graph acyclic.
 *
 * <p><strong>It carries no payment consequence.</strong> The roadmap's rule is
 * that an arrival mismatch is "a discrepancy record, not a reopened payment": the
 * match never re-runs, no payment transitions, closure doesn't move. The
 * discrepancy's system of record is the shipments-owned {@code ArrivalDiscrepancy};
 * this event just tells the credit lane a shortfall/overage is waiting for a
 * resolver (whose action is a 6.7 credit — {@code SHORT_SHIPMENT} is the ledger's
 * first named cause). No consumer is built yet; publishing it is a harmless no-op
 * that models the seam.
 */
@NamedInterface("payment-events")
public record ArrivalDiscrepancyEvent(UUID purchaseOrderId, UUID consignmentId) {
}
