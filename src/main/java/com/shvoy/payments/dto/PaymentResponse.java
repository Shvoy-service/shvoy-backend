package com.shvoy.payments.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.shvoy.Money;
import com.shvoy.payments.domain.PaymentStatus;
import com.shvoy.payments.domain.PaymentType;

/**
 * A single payment after a Pay/Hold/Release action (Story 6.8) — the current
 * status the frontend renders its buttons off, plus the paid record when set.
 */
public record PaymentResponse(
    UUID paymentId,
    UUID purchaseOrderId,
    PaymentType type,
    Money amount,
    PaymentStatus status,
    LocalDate dueDate,
    LocalDate paidDate,
    String paymentReference,
    String matchDetail
) {
}
