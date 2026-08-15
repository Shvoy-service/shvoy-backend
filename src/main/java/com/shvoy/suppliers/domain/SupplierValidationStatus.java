package com.shvoy.suppliers.domain;

/**
 * The supplier-validation lifecycle (supplier remodel) — distinct from {@link
 * SupplierStatus} (ACTIVE/INACTIVE soft-delete). A supplier is PO-eligible only
 * once a human has {@code VALIDATED} it. New suppliers start {@code PENDING};
 * changing bank details on a {@code VALIDATED} supplier reverts it to {@code
 * PENDING} (the invoice-fraud control). What validation gates — PO issuance —
 * is the next story.
 */
public enum SupplierValidationStatus {
    PENDING,
    VALIDATED
}
