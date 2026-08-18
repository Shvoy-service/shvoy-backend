package com.shvoy.payments.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Size;

/**
 * The Pay action (Story 6.8) — both fields optional. {@code paidDate} defaults to
 * the action date when omitted, but is overridable (Finance often records a
 * payment a day after execution, so forcing today's date makes the record wrong).
 * {@code paymentReference} is a free-text bank ref / batch id.
 */
public record PayPaymentRequest(
    LocalDate paidDate,
    @Size(max = 200) String paymentReference
) {
}
