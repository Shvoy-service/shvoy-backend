package com.shvoy.suppliers.dto;

/**
 * A supplier's terms as read back (supplier remodel) — the current term (null
 * until set) and the optional target term held mid-transition (the visibility
 * Finance asked for).
 */
public record SupplierPaymentTermsResponse(
    PaymentTermsResponse current,
    PaymentTermsResponse target
) {
}
