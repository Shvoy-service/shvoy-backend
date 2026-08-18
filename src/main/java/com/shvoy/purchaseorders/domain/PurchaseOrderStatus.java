package com.shvoy.purchaseorders.domain;

/**
 * DRAFT/GENERATED/SENT cover the feature's own lifecycle (3.1); CANCELLED
 * (4.4) is a draft's soft-delete/abandon terminal state — only reachable
 * from DRAFT (see PurchaseOrder#cancel), never from GENERATED/SENT, which
 * have their own separate lifecycle not modelled here yet. Later features
 * (confirmation via PI, closure) extend this further rather than tracking
 * their own state elsewhere — CLOSED/CLOSED_SHORT are the receipt-rollup &
 * PO-closure lifecycle (closure is observed from receipt, not commanded).
 */
public enum PurchaseOrderStatus {
    DRAFT,
    GENERATED,
    SENT,
    /** Cumulative received = ordered per SKU exactly — an observed receipt fact, not a command (receipt rollup & PO closure). */
    CLOSED,
    /** Finance/Admin write-off of an undelivered remainder — a distinct fact from CLOSED (completion vs abandonment). */
    CLOSED_SHORT,
    CANCELLED
}
