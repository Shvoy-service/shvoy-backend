package com.shvoy.payments.dto;

import java.util.UUID;

/** A claimed line on a {@code LINES}-coverage invoice, as read back (invoice remodel). */
public record InvoiceCoveredLineResponse(
    UUID skuId,
    int quantity
) {
}
