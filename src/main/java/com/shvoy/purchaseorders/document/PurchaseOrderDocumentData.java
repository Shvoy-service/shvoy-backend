package com.shvoy.purchaseorders.document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.shvoy.Money;
import com.shvoy.UnitPrice;

/**
 * Everything the PO document template needs, and nothing it doesn't — the
 * "clean PO representation" Story 4.6 asks for, kept deliberately separate
 * from {@code PurchaseOrderResponse} (the JSON API shape, which carries
 * fields like {@code createdBy}/{@code status} the document has no reason
 * to show) and from the domain entities themselves (the template should
 * never need to know how a {@code PurchaseOrder}/{@code PurchaseOrderLine}
 * is persisted). {@code PurchaseOrderGenerationService} assembles one of
 * these from the just-snapshotted PO plus a {@code SupplierSummary}/{@code
 * SkuSummary} per line (both cross-module lookups — see those types'
 * Javadoc); {@link PurchaseOrderDocumentRenderer} only ever reads this,
 * never anything upstream of it.
 */
public record PurchaseOrderDocumentData(
    String poNumber,
    String supplierName,
    String supplierCountry,
    String supplierContactEmail,
    LocalDate requestedEtd,
    List<LineItem> lines,
    Money orderTotal,
    Money deposit,
    Money balance,
    Instant generatedAt
) {

    public record LineItem(
        String skuCode,
        String skuDescription,
        int quantity,
        UnitPrice unitPrice,
        Integer appliedTierThreshold,
        Money lineTotal
    ) {
    }
}
