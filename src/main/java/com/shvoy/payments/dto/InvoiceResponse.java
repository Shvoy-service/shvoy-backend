package com.shvoy.payments.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.shvoy.Money;
import com.shvoy.payments.domain.InvoiceStatus;

/**
 * A logged invoice (Story 6.4) — header-level: reference, amount, date, and any
 * claimed credit. {@code active} distinguishes the current invoice from ones it
 * superseded. {@code claimedCredit} is null when the supplier claimed none.
 */
public record InvoiceResponse(
    UUID id,
    UUID purchaseOrderId,
    String invoiceReference,
    Money amount,
    LocalDate invoiceDate,
    Money claimedCredit,
    String claimedCreditReference,
    InvoiceStatus status,
    boolean active,
    UUID loggedBy,
    Instant createdAt,
    Instant updatedAt
) {
}
