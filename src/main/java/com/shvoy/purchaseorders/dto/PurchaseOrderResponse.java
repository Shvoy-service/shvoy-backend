package com.shvoy.purchaseorders.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.shvoy.Money;
import com.shvoy.purchaseorders.domain.Incoterms;
import com.shvoy.purchaseorders.domain.PurchaseOrderStatus;

/**
 * Every mutation (4.4's create/add-line/edit-line/remove-line/set-etd, 4.6's
 * generate, 4.7's send) returns this full representation — lines and
 * totals included — rather than a bare ack, so a caller never needs a
 * follow-up GET to see what its own write actually produced (same
 * reasoning as SkuWithPriceResponse).
 *
 * {@code generatedBy}/{@code generatedAt} are null until {@link
 * com.shvoy.purchaseorders.domain.PurchaseOrder#applyGeneration} runs
 * (Story 4.6) — the PDF itself isn't embedded here; it's fetched separately
 * via {@code GET .../document}, the same way any other binary download
 * works rather than being base64-inlined into a JSON body.
 *
 * {@code sentBy}/{@code sentAt} reflect the **most recent** send (Story
 * 4.7) — null until the PO has been sent at least once. A PO can be sent
 * more than once (resend is allowed — see {@code PurchaseOrderSendService}),
 * each one its own {@code PurchaseOrderSend} audit row, but this response
 * only ever surfaces the latest; the full send history isn't exposed by any
 * endpoint yet.
 */
public record PurchaseOrderResponse(
    UUID id,
    UUID supplierId,
    String poNumber,
    PurchaseOrderStatus status,
    LocalDate requestedEtd,
    Incoterms incoterms,
    String contractReference,
    String deliveryAddress,
    String budgetCode,
    boolean contractPending,
    boolean compliancePending,
    // Receipt rollup & PO closure: cumulative received exceeds ordered on a SKU (interim, holds closure).
    boolean overDelivered,
    UUID createdBy,
    Money orderTotal,
    Money deposit,
    Money balance,
    List<PurchaseOrderLineResponse> lines,
    Instant createdAt,
    Instant updatedAt,
    UUID generatedBy,
    Instant generatedAt,
    UUID sentBy,
    Instant sentAt
) {
}
