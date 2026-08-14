package com.shvoy.suppliers.dto;

import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/**
 * A narrow, read-only view of a supplier for another module to display —
 * first needed by Story 4.6's PO document, which shows the supplier's name/
 * country/contact rather than a bare id. Deliberately not
 * {@code SupplierResponse} (the full API response shape, carrying status/
 * timestamps a customer-facing document has no business showing) — this is
 * its own minimal cross-module contract, same reasoning as
 * {@code PriceResolutionResult}/{@code PaymentSplit}.
 */
@NamedInterface("suppliers")
public record SupplierSummary(
    UUID id,
    String name,
    String country,
    String contactEmail
) {
}
