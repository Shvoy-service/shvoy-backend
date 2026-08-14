package com.shvoy.reconciliation.dto;

/**
 * An approver's sign-off on a routed PI (Story 5.5). The comment is optional
 * — an approval doesn't demand a justification the way a rejection does — but
 * it's recorded immutably when supplied.
 */
public record ApproveProformaInvoiceRequest(
    String comment
) {
}
