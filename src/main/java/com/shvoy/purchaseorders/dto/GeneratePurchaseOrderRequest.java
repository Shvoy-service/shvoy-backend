package com.shvoy.purchaseorders.dto;

/**
 * {@code override} is nullable — omitted (or its whole body omitted)
 * entirely for the common case: generating a draft with no expired-price
 * lines. Deliberately no Bean Validation annotations, same reasoning as
 * {@link ExpiredPriceOverrideRequest}'s own Javadoc — an incomplete
 * override attempt (blank reason, or a missing manual price) is meant to
 * surface as {@code PO_HAS_EXPIRED_PRICES}/409 from {@code
 * PurchaseOrderFinalisationGateService}, not a blanket {@code
 * VALIDATION_ERROR}/400 that says nothing about which lines are affected.
 */
public record GeneratePurchaseOrderRequest(
    ExpiredPriceOverrideRequest override
) {
}
