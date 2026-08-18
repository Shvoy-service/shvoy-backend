package com.shvoy.suppliers.dto;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/**
 * A supplier's price-file warning row (Story 9.2) — the rollup Screen 2 and the
 * dashboard render: the derived {@link PriceFileStatus} plus the counts a user
 * acts on ("Supplier X's prices need renewing"). The per-SKU breakdown is the
 * drill-down ({@link SkuPriceWarning}), not carried here.
 *
 * <p>{@code expiredCount} counts SKUs with no valid price today (lapsed +
 * never-priced); {@code neverPricedCount} is the subset that was never priced at
 * all (the two cases 4.5 distinguishes). {@code earliestExpiry} is the soonest
 * {@code validTo} among the expiring-soon SKUs — the "renew before" date; null
 * when nothing is merely expiring (e.g. an already-expired supplier).
 */
@NamedInterface("price-warnings")
public record SupplierPriceWarning(
    UUID supplierId,
    String supplierName,
    PriceFileStatus status,
    int expiredCount,
    int neverPricedCount,
    int expiringCount,
    LocalDate earliestExpiry
) {
}
