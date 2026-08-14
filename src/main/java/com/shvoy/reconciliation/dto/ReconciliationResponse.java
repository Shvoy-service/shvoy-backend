package com.shvoy.reconciliation.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.shvoy.reconciliation.domain.VarianceBasis;

/**
 * The stored three-way comparison for a logged PI (Story 5.3) — the header
 * plus its per-line legs/variances/findings. Read-only: this story records
 * the comparison; it takes no tolerance or routing decision (5.4), so there's
 * no outcome or status on it yet.
 *
 * {@code varianceBasis} records which basis the variances were computed on
 * (the still-open Product Owner question — see {@link VarianceBasis}), and
 * {@code priceFileAsOfDate} the date the price-file leg was resolved against
 * (the PO's generation date), both for auditability. {@code currencyMismatch}
 * flags a PI whose currency differs from the PO's — recorded, never
 * auto-converted.
 */
public record ReconciliationResponse(
    UUID id,
    UUID proformaInvoiceId,
    UUID purchaseOrderId,
    VarianceBasis varianceBasis,
    LocalDate priceFileAsOfDate,
    String poCurrency,
    String piCurrency,
    boolean currencyMismatch,
    List<ReconciliationLineResponse> lines,
    Instant createdAt
) {
}
