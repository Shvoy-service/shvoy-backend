package com.shvoy.purchaseorders.domain;

/** Audit vocabulary for a PO's advisory issuance flags (PO-issuance gate). */
public enum PurchaseOrderAuditEventType {
    ADVISORY_FLAGS_STAMPED,
    CONTRACT_PENDING_CLEARED,
    COMPLIANCE_PENDING_CLEARED
}
