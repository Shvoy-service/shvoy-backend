package com.shvoy.purchaseorders.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The manual price a user is asserting for one blocked line as part of a
 * Story 4.5 expired-price override — see {@link ExpiredPriceOverrideRequest}.
 */
public record LineOverridePrice(
    UUID lineId,
    BigDecimal unitPriceAmount,
    String currency
) {
}
