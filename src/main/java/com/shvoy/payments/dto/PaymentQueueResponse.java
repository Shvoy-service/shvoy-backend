package com.shvoy.payments.dto;

import java.util.List;

/**
 * A page of the payment queue (Story 6.3) — the rows for this page plus the
 * paging metadata a table needs. {@code totalCount} is the number of payments
 * matching the filters (before paging), so the UI can show "showing 1–50 of N"
 * and page controls without a second call.
 */
public record PaymentQueueResponse(
    List<PaymentQueueRow> payments,
    int page,
    int size,
    long totalCount
) {
}
