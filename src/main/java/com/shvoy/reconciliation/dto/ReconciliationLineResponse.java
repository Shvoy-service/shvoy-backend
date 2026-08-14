package com.shvoy.reconciliation.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.shvoy.UnitPrice;
import com.shvoy.reconciliation.domain.ReconciliationFindingType;

/**
 * One line of a {@link ReconciliationResponse} — the three legs and the
 * computed variances for a matched line, or the partial legs of a structural
 * finding (see {@link ReconciliationFindingType}). Null fields are meaningful:
 * an absent leg (unmatched/duplicate) or a variance that couldn't be computed
 * (cross-currency, so meaningless without an FX rate).
 *
 * {@code unitPriceVariancePct}/{@code quantityVariancePct} are the signed,
 * 2dp values exactly as stored and compared (Story 5.3); {@code
 * unitPriceVarianceDirection} is the same sign surfaced explicitly for
 * convenience. The quantity legs also expose the absolute difference
 * ({@code quantityVarianceAbs}, PI − PO) alongside the percentage — the
 * price-file leg has no quantity of its own, it's a price reference only.
 */
public record ReconciliationLineResponse(
    UUID skuId,
    ReconciliationFindingType findingType,
    UnitPrice poUnitPrice,
    Integer poQuantity,
    UnitPrice piUnitPrice,
    Integer piQuantity,
    UnitPrice priceFileUnitPrice,
    Boolean priceFilePriceFound,
    BigDecimal unitPriceVariancePct,
    VarianceDirection unitPriceVarianceDirection,
    BigDecimal quantityVariancePct,
    Integer quantityVarianceAbs
) {
}
