package com.shvoy.shipments.dto;

import java.util.UUID;

/** One per-SKU received quantity on a provisional GRN (Story 7.4). */
public record GoodsReceiptLineResponse(
    UUID skuId,
    int receivedQuantity
) {
}
