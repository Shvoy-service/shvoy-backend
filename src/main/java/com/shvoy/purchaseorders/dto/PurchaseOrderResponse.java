package com.shvoy.purchaseorders.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.shvoy.Money;
import com.shvoy.purchaseorders.domain.PurchaseOrderStatus;

/**
 * Every mutation (4.4's create/add-line/edit-line/remove-line/set-etd, 4.6's
 * generate) returns this full representation — lines and totals included —
 * rather than a bare ack, so a caller never needs a follow-up GET to see
 * what its own write actually produced (same reasoning as SkuWithPriceResponse).
 *
 * {@code generatedBy}/{@code generatedAt} are null until {@link
 * com.shvoy.purchaseorders.domain.PurchaseOrder#applyGeneration} runs
 * (Story 4.6) — the PDF itself isn't embedded here; it's fetched separately
 * via {@code GET .../document}, the same way any other binary download
 * works rather than being base64-inlined into a JSON body.
 */
public record PurchaseOrderResponse(
    UUID id,
    UUID supplierId,
    String poNumber,
    PurchaseOrderStatus status,
    LocalDate requestedEtd,
    UUID createdBy,
    Money orderTotal,
    Money deposit,
    Money balance,
    List<PurchaseOrderLineResponse> lines,
    Instant createdAt,
    Instant updatedAt,
    UUID generatedBy,
    Instant generatedAt
) {
}
