package com.shvoy.purchaseorders.domain;

/**
 * Deliberately minimal for Story 4.1 — DRAFT/GENERATED/SENT covers this
 * feature's own lifecycle; later features (confirmation via PI, closure)
 * extend this rather than tracking their own state elsewhere, but that
 * extension isn't needed yet.
 */
public enum PurchaseOrderStatus {
    DRAFT,
    GENERATED,
    SENT
}
