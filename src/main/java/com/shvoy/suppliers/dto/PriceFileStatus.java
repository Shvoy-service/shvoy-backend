package com.shvoy.suppliers.dto;

import org.springframework.modulith.NamedInterface;

/**
 * A supplier's derived price-file health (Story 9.2) — rolled up from its active
 * SKUs' <em>current</em> price resolution (3.8), never stored. {@code EXPIRED}
 * dominates: a supplier with both an expired and an expiring SKU shows
 * {@code EXPIRED}.
 * <ul>
 *   <li>{@code EXPIRED} — at least one active SKU has no valid price today
 *       (its window lapsed, or it was never priced).</li>
 *   <li>{@code EXPIRING_SOON} — none expired, but at least one current price's
 *       {@code validTo} falls within the warning window (14 days, inclusive).</li>
 *   <li>{@code IN_DATE} — everything valid beyond the window (open-ended prices,
 *       {@code validTo} null, are永 in-date and never warn).</li>
 * </ul>
 */
@NamedInterface("price-warnings")
public enum PriceFileStatus {
    IN_DATE,
    EXPIRING_SOON,
    EXPIRED
}
