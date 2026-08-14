package com.shvoy.suppliers.dto;

import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/**
 * A narrow, read-only view of a SKU for another module to display — first
 * needed by Story 4.6's PO document, which shows each line's SKU code/
 * description rather than a bare id (a {@code PurchaseOrderLine} only
 * stores {@code skuId} — see its class Javadoc). Deliberately not {@code
 * SkuResponse} (the full API response shape) — same minimal-cross-module-
 * contract reasoning as {@link SupplierSummary}.
 */
@NamedInterface("suppliers")
public record SkuSummary(
    UUID id,
    String code,
    String description
) {
}
