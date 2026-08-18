package com.shvoy.suppliers.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One SKU's price-warning detail (Story 9.2) — the drill-down beneath a
 * supplier's rollup (Screen 2's expanded row). Suppliers-internal (the dashboard
 * shows only the rollup), so not a cross-module interface.
 */
public record SkuPriceWarning(
    UUID skuId,
    String skuCode,
    Reason reason,
    LocalDate validTo
) {
    /** Why this SKU warns. {@code LAPSED}/{@code NEVER_PRICED} → expired; {@code EXPIRING} → a current price ending soon. */
    public enum Reason {
        LAPSED,
        NEVER_PRICED,
        EXPIRING
    }
}
