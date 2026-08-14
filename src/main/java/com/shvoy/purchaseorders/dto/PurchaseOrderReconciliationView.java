package com.shvoy.purchaseorders.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/**
 * Everything the reconciliation module (Story 5.3) needs to build the PO leg
 * of a variance comparison, in one narrow read-only view: the PO's supplier
 * (to re-resolve the price-file leg via 3.8), the PO's currency (to detect a
 * cross-currency PI), the generation date (the as-of date the price-file leg
 * is resolved against — see below), and the snapshotted lines.
 *
 * {@code generationDate} is the {@code LocalDate} of the PO's generation
 * instant (UTC, per the dates contract). It's used as the as-of date for the
 * price-file leg deliberately: resolving the price file as of when the order
 * was actually raised makes that leg a check on whether the PO was priced
 * correctly at the time, rather than conflating "the PO was mispriced" with
 * "the price file has changed since" — see docs/CONTRACT.md's PI
 * reconciliation section. Equal to each line's own {@code pricedAsOfDate}
 * snapshot for a normally-generated PO (4.6 re-resolves every line as of the
 * generation date), so the price-file leg reproduces exactly what the PO leg
 * should have resolved to.
 *
 * {@code currency} is the PO's single currency (the single-currency-per-PO
 * rule, 4.2) — null only for a PO with no priced lines, which a
 * {@code GENERATED}/{@code SENT} PO never is.
 */
@NamedInterface("purchase-orders")
public record PurchaseOrderReconciliationView(
    UUID purchaseOrderId,
    UUID supplierId,
    String currency,
    LocalDate generationDate,
    List<PurchaseOrderReconciliationLine> lines
) {
}
