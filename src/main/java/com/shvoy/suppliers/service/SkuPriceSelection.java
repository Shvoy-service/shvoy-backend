package com.shvoy.suppliers.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.shvoy.suppliers.domain.SkuPrice;

/**
 * Single source of truth for "which {@link SkuPrice} row is the effective
 * one" on a SKU's timeline. Shared by {@link PriceResolutionService} (the
 * price valid on a given date — Story 3.8) and {@link SkuService#listSkus}
 * (the supplier screen's current price + derived in-date flag), so those two
 * surfaces — and, through resolution, the PO-creation gate and the expiry
 * warnings — can never disagree about a SKU's price state. The tiebreak
 * lives here once rather than being re-implemented per caller; the agreement
 * is proven by {@code SupplierSkuReadAgreementTest}.
 */
final class SkuPriceSelection {

    private SkuPriceSelection() {
    }

    /**
     * The current/effective price on a SKU's non-overlapping timeline: the
     * open row ({@code validTo == null}) if one exists, else the row with
     * the latest {@code validFrom}. Empty only when the SKU has no prices at
     * all (never priced).
     *
     * <p>Under the Story 3.5 supersession invariant the open row always
     * carries the latest {@code validFrom}, so on valid data this is exactly
     * "the latest price version" — the same row {@link PriceResolutionService}
     * lands on when resolving today. The explicit open-row preference only
     * bites on an already-corrupt (overlapping) timeline, where it keeps the
     * choice deterministic; the same comparator is applied to the in-date
     * candidates during date resolution, so the two paths never split on the
     * tiebreak.
     */
    static Optional<SkuPrice> current(List<SkuPrice> pricesForSku) {
        return pricesForSku.stream().max(
            Comparator.comparing((SkuPrice p) -> p.getValidTo() == null)
                .thenComparing(SkuPrice::getValidFrom));
    }
}
