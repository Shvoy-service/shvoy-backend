package com.shvoy.payments.event;

import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/**
 * "A provisional GRN was created despite a failed inspection" (Story 7.4
 * revised) — the seam a future NCR / quality-dispute flow hangs from. The goods
 * are physically real and on the water, so the GRN and the three-way match (6.5)
 * proceed <strong>unblocked</strong>; the quality conversation runs alongside
 * payment control, in the credit/dispute lane, not by blocking receipt.
 *
 * <p><strong>Owned by the consuming side ({@code payments})</strong> and
 * published by {@code shipments}, exactly like {@link
 * ProvisionalGoodsReceiptEvent} and the anchor seam — {@code shipments} already
 * depends on {@code payments}, so a payments-owned inbound event keeps the module
 * graph acyclic. No one consumes it yet (no NCR machinery is built pending the
 * phasing decision); publishing it is a harmless no-op that models the seam.
 */
@NamedInterface("payment-events")
public record QcFailureEvent(UUID purchaseOrderId, UUID consignmentId) {
}
