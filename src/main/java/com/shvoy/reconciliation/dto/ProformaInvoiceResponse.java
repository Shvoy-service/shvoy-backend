package com.shvoy.reconciliation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.shvoy.reconciliation.domain.ProformaInvoiceStatus;

/**
 * Returned by both the logging endpoint and the read endpoints (Story 5.2)
 * — lines included, same reasoning as {@code PurchaseOrderResponse}: a
 * caller never needs a follow-up GET to see what its own write produced.
 * {@code status} is {@code LOGGED} for every PI this story can produce —
 * 5.4/5.7 give the other values real transitions.
 */
public record ProformaInvoiceResponse(
    UUID id,
    UUID purchaseOrderId,
    String piReference,
    String currency,
    ProformaInvoiceStatus status,
    boolean active,
    UUID loggedBy,
    List<ProformaInvoiceLineResponse> lines,
    Instant createdAt,
    Instant updatedAt
) {
}
