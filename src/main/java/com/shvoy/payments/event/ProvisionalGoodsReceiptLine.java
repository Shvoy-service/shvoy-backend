package com.shvoy.payments.event;

import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/**
 * One received per-SKU quantity carried on a {@link ProvisionalGoodsReceiptEvent}
 * (Story 7.4), in {@code payments}' own vocabulary — never a {@code shipments}
 * type, which would re-introduce the {@code payments ↔ shipments} cycle.
 * Exposed on the {@code payment-events} named interface so {@code shipments} can
 * build the event it publishes.
 */
@NamedInterface("payment-events")
public record ProvisionalGoodsReceiptLine(UUID skuId, int receivedQuantity) {
}
