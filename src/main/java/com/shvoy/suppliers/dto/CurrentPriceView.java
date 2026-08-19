package com.shvoy.suppliers.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.shvoy.UnitPrice;

/**
 * A SKU's current price for the supplier-screen read
 * ({@code GET /api/suppliers/{id}/skus}) — the same fields as
 * {@link SkuPriceResponse}, plus a derived {@code inDate} flag. Distinct
 * from {@code SkuPriceResponse} precisely because of that flag: the supplier
 * screen needs to show whether the current price is in-date or expired
 * without a second call, whereas the write-path {@code SkuPriceResponse}
 * (returned from create/add-price) has no reason to carry it.
 *
 * {@code inDate} is derived at read time from {@code SkuPrice#isInDate(today)}
 * — the same not-stored derivation the wireframe's in-date/expired badge
 * uses (see docs/CONTRACT.md's SKU &amp; price model section), never a stored
 * column that could drift. An expired current price still appears here with
 * {@code inDate: false}; it is not hidden — hiding it would make an expired
 * supplier look unpriced.
 */
public record CurrentPriceView(
    UUID id,
    UUID skuId,
    UnitPrice unitPrice,
    LocalDate validFrom,
    LocalDate validTo,
    boolean inDate,
    Instant createdAt,
    Instant updatedAt
) {
}
