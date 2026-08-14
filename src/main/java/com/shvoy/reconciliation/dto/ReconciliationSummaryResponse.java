package com.shvoy.reconciliation.dto;

import java.time.Instant;
import java.util.UUID;

import com.shvoy.reconciliation.domain.ProformaInvoiceStatus;
import com.shvoy.reconciliation.domain.ReconciliationOutcome;

/**
 * A row in a reconciliation list (Story 5.7) — the per-PO history (including
 * superseded PIs) and the approver's pending-approval queue. Carries enough to
 * identify and triage a reconciliation without the full comparison; the detail
 * endpoint returns the rest.
 */
public record ReconciliationSummaryResponse(
    UUID proformaInvoiceId,
    UUID purchaseOrderId,
    UUID supplierId,
    String piReference,
    String currency,
    ProformaInvoiceStatus status,
    boolean active,
    ReconciliationOutcome outcome,
    Instant reconciledAt
) {
}
