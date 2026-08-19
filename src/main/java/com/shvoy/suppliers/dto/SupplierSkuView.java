package com.shvoy.suppliers.dto;

import java.util.List;

/**
 * One SKU as the supplier screen needs it in a single list read
 * ({@code GET /api/suppliers/{id}/skus}, Story — supplier SKU read endpoint):
 * the SKU's own fields, its <em>current</em> price (with a derived in-date
 * flag), and that price's discount tiers inline — so the frontend types
 * against one shape and one call, rather than fanning out to
 * price-resolution and the tiers endpoint per SKU.
 *
 * The {@code sku}/{@code currentPrice} nesting deliberately mirrors
 * {@link SkuWithPriceResponse} (the create response) rather than inventing a
 * flat shape, and reuses {@link SkuResponse} verbatim so the two can't drift.
 *
 * {@code currentPrice} is {@code null} when the SKU has never been priced (a
 * legitimate state — the model doesn't require a price to exist); it is
 * never an empty object. {@code tiers} is an empty list — never {@code null}
 * — when the current price is null or simply has no tiers, so the frontend
 * iterates it unconditionally. History (a SKU's full price timeline) is
 * deliberately excluded: it's heavy and would be a separate read if ever
 * needed. See docs/CONTRACT.md's SKU &amp; price model section for the pinned
 * shape.
 */
public record SupplierSkuView(
    SkuResponse sku,
    CurrentPriceView currentPrice,
    List<DiscountTierResponse> tiers
) {
}
