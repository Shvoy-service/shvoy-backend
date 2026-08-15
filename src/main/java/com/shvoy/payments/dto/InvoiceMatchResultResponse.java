package com.shvoy.payments.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.shvoy.payments.domain.InvoiceCoversType;
import com.shvoy.payments.domain.MatchPolicy;

/**
 * One invoice's current match outcome (Story 6.5 re-spec) — what the strategy
 * decided and what the terms-type policy did with it. {@code positionMatched}
 * flags a loose AMOUNT reconciliation. The statement view (later) and Finance
 * read these per PO.
 */
public record InvoiceMatchResultResponse(
    UUID invoiceId,
    InvoiceCoversType coversType,
    String termsType,
    boolean passed,
    boolean positionMatched,
    BigDecimal expectedAmount,
    BigDecimal invoiceAmount,
    String currency,
    String detail,
    MatchPolicy policyApplied,
    Instant evaluatedAt
) {
}
