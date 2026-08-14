package com.shvoy.purchaseorders.domain;

/**
 * DRAFT/GENERATED/SENT cover the feature's own lifecycle (3.1); CANCELLED
 * (4.4) is a draft's soft-delete/abandon terminal state — only reachable
 * from DRAFT (see PurchaseOrder#cancel), never from GENERATED/SENT, which
 * have their own separate lifecycle not modelled here yet. Later features
 * (confirmation via PI, closure) extend this further rather than tracking
 * their own state elsewhere.
 */
public enum PurchaseOrderStatus {
    DRAFT,
    GENERATED,
    SENT,
    CANCELLED
}
