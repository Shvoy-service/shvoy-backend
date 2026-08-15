package com.shvoy.payments.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.shvoy.Money;
import com.shvoy.payments.domain.InvoiceCoversType;
import com.shvoy.payments.domain.InvoiceStatus;

/**
 * A logged invoice (Story 6.4) — header-level: reference, amount, date, and any
 * claimed credit. {@code active} distinguishes the current invoice from ones it
 * superseded. {@code coversType} is what it declares it covers (invoice
 * remodel); {@code weakestSignal} flags the AMOUNT fallback. {@code
 * claimedCredit} is null when the supplier claimed none.
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
    // What the invoice declares it covers (invoice remodel).
    InvoiceCoversType coversType,
    UUID coversConsignmentId,
    List<InvoiceCoveredLineResponse> coveredLines,
    // The specific invoice this one corrects, if it's a correction (else null).
    UUID supersedesInvoiceId,
    // True when coversType == AMOUNT: accepted, but the weakest reconciliation signal — surfaced so
    // a reviewer sees it wasn't tied to a shipment/lines/deposit/balance.
    boolean weakestSignal,
    UUID loggedBy,
    Instant createdAt,
    Instant updatedAt
) {
}
