package com.shvoy.reconciliation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.shvoy.reconciliation.domain.ReconciliationOutcome;
import com.shvoy.reconciliation.domain.RoutingReason;
import com.shvoy.reconciliation.domain.VarianceBasis;

/**
 * The stored three-way comparison for a logged PI (Story 5.3) plus its 5.4
 * outcome — the header, the per-line legs/variances/findings, and the
 * auto-confirm-vs-route decision.
 *
 * {@code varianceBasis} records which basis the variances were computed on
 * (see {@link VarianceBasis}), and {@code priceFileAsOfDate} the date the
 * price-file leg was resolved against (the PO's generation date), both for
 * auditability. {@code currencyMismatch} flags a PI whose currency differs
 * from the PO's — recorded, never auto-converted.
 *
 * {@code outcome} is the whole-PI decision (5.4), {@code toleranceApplied}
 * the tolerance % it was evaluated against, and {@code routingReasons} why it
 * routed (empty when auto-confirmed) — derived deterministically from the
 * stored lines, the currency-mismatch flag, and {@code toleranceApplied}, so
 * the approver and Screen 4 see exactly what drove the outcome. All three are
 * null/empty on a record computed but not yet evaluated.
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
    ReconciliationOutcome outcome,
    BigDecimal toleranceApplied,
    List<RoutingReason> routingReasons,
    List<ReconciliationLineResponse> lines,
    Instant createdAt
) {
}
