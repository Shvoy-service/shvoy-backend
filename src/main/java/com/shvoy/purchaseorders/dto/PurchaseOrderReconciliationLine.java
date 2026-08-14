package com.shvoy.purchaseorders.dto;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/**
 * One PO line's snapshot, as the reconciliation module needs it for the PO
 * leg of a variance comparison (Story 5.3) — the SKU, the ordered quantity,
 * and the 4dp unit price the PO actually carried when it was generated
 * (Feature 4's price snapshot, never a live re-resolve — that's the whole
 * point of the snapshot). Deliberately narrow, same minimal-cross-module-
 * contract reasoning as {@code SkuSummary}/{@code SupplierSummary}: it
 * exposes only what the comparison reads, not the full {@code
 * PurchaseOrderLineResponse} shape or the domain entity.
 *
 * {@code unitPriceAmount} is null only for a line that was never priced —
 * which a {@code GENERATED}/{@code SENT} PO's lines never are (4.6's
 * generation gate blocks finalisation otherwise), so in practice it's always
 * present by the time a PI can be logged against the PO; {@code priceFound}
 * carries the distinction rather than a bare null, same convention as the
 * line's own snapshot.
 */
@NamedInterface("purchase-orders")
public record PurchaseOrderReconciliationLine(
    UUID skuId,
    int quantity,
    BigDecimal unitPriceAmount,
    boolean priceFound
) {
}
