package com.shvoy.suppliers.domain;

/** The audit vocabulary for sensitive supplier actions (supplier remodel). */
public enum SupplierAuditEventType {
    VALIDATED,
    UNVALIDATED,
    BANK_DETAILS_CHANGED,
    BANK_CHANGE_REVERTED_VALIDATION,
    TERMS_TARGET_ACTIVATED
}
