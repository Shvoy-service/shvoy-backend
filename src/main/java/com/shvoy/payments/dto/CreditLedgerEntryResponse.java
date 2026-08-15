package com.shvoy.payments.dto;

import java.time.Instant;
import java.util.UUID;

import com.shvoy.Money;
import com.shvoy.payments.domain.CreditCause;
import com.shvoy.payments.domain.CreditLedgerStatus;

/**
 * A credit ledger entry (Story 6.7). {@code targetInvoiceId} is the invoice
 * this credit applies against — null while OPEN and awaiting its (future)
 * invoice, set when applied. {@code closureReason} is set only on {@code
 * CANCELLED}; {@code ncrReference} only for an NCR-caused credit (a seam).
 */
public record CreditLedgerEntryResponse(
    UUID id,
    UUID purchaseOrderId,
    Money amount,
    CreditCause cause,
    String causeDetail,
    String ncrReference,
    UUID targetInvoiceId,
    CreditLedgerStatus status,
    String closureReason,
    UUID loggedBy,
    Instant createdAt,
    Instant updatedAt
) {
}
