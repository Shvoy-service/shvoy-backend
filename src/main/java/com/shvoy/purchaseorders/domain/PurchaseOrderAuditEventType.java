package com.shvoy.purchaseorders.domain;

/** Audit vocabulary for a PO's advisory issuance flags (PO-issuance gate) and its receipt-driven closure lifecycle (receipt rollup & PO closure). */
public enum PurchaseOrderAuditEventType {
    ADVISORY_FLAGS_STAMPED,
    CONTRACT_PENDING_CLEARED,
    COMPLIANCE_PENDING_CLEARED,
    /** Receipt rollup & PO closure. */
    PO_CLOSED_ON_RECEIPT,
    PO_REOPENED_ON_AMENDMENT,
    PO_CLOSED_SHORT,
    OVER_DELIVERY_FLAGGED,
    OVER_DELIVERY_CLEARED
}
