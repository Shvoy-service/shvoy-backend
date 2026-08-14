package com.shvoy.suppliers.dto;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;

import com.shvoy.UnitPrice;

/**
 * The answer to "what does this SKU cost, from this supplier, at this
 * quantity, on this date?" — see Story 3.8, PriceResolutionService. This is
 * the Feature 3 → Feature 4/5 contract: Feature 4 (PO pricing) and Feature 5
 * (PI reconciliation) both bind to this shape, so it's exposed as its own
 * named interface the same way {@code onboarding.domain.Role} is — other
 * modules can depend on this result directly without gaining access to the
 * rest of the (otherwise internal) suppliers module.
 *
 * {@code priceFound} is the stable way to detect "no valid price for this
 * date" — never a thrown exception (a SKU genuinely having no price
 * covering the as-of date is an expected resolution outcome, not a fault),
 * and never a silent fallback to some other price. When {@code false},
 * {@code skuPriceId}/{@code unitPrice}/{@code appliedTierThreshold} are all
 * null — there is nothing to report.
 *
 * {@code cartonValid}/{@code adjustedQuantity} are populated regardless of
 * whether a price was found: carton size lives on the SKU (3.7), not the
 * price, so it's independent of price resolution succeeding.
 * {@code adjustedQuantity} is always set (equal to the requested quantity
 * when it's already a valid carton multiple, or when the SKU has no carton
 * size at all) — computed via {@code Sku#nearestCartonMultiple}, never
 * reimplemented here, so the carton rule has exactly one place to change
 * (see that method's Javadoc for the still-open "nearest vs. round-up"
 * Product Owner question this inherits).
 *
 * {@code appliedTierThreshold} is null when the base {@code SkuPrice} price
 * applied (no tier, or the quantity fell below the lowest threshold) —
 * distinguishing "a tier was applied" from "the base price applied" is
 * exactly what Screen 3's "discount tier applied" indicator needs.
 *
 * {@code everPriced} (added Story 4.5) distinguishes, when
 * {@code priceFound} is {@code false}, *why*: {@code true} means the SKU
 * has at least one {@code SkuPrice} row somewhere in its history — a price
 * that has **expired** (or, once future-dated prices are supported, not
 * yet started) — versus {@code false}, meaning the SKU has **never** had a
 * price at all, nothing to fall back on. Irrelevant (always {@code true})
 * when {@code priceFound} is {@code true}. Story 4.5's override flow needs
 * this distinction: an "expired" line has a last-known value that could be
 * shown/reused; a "never priced" line has nothing.
 */
@NamedInterface("price-resolution")
public record PriceResolutionResult(
    boolean priceFound,
    UUID skuPriceId,
    UnitPrice unitPrice,
    Integer appliedTierThreshold,
    LocalDate asOfDate,
    boolean cartonValid,
    int adjustedQuantity,
    boolean everPriced
) {
}
