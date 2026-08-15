package com.shvoy.payments.dto;

import java.time.Instant;
import java.util.UUID;

import com.shvoy.payments.domain.DiscrepancyResolutionType;
import com.shvoy.payments.domain.DiscrepancyStatus;

/** One row of the discrepancy queue (Story 6.6) — the claimable list of blocked payments. */
public record DiscrepancyCaseSummary(
    UUID caseId,
    UUID paymentId,
    UUID purchaseOrderId,
    String poNumber,
    DiscrepancyStatus status,
    DiscrepancyResolutionType resolutionType,
    String failureDetail,
    UUID claimedBy,
    Instant createdAt
) {
}
