package com.shvoy.reconciliation.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A rejection of a routed PI (Story 5.5). The reason is required — a
 * rejection means the discrepancy needs resolving with the supplier, so the
 * "why" is recorded immutably for whoever picks that up.
 */
public record RejectProformaInvoiceRequest(
    @NotBlank String reason
) {
}
