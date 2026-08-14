package com.shvoy.reconciliation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.shvoy.TenantScoped;

/**
 * The header of one three-way comparison — PO vs PI vs price-file formula —
 * for a single logged PI (Story 5.3). Produced automatically by the post-log
 * trigger (5.2's seam), one per PI. The per-line legs, variances, and
 * structural findings live on {@link ReconciliationLine} rows.
 *
 * 5.3 computes and records the raw comparison; 5.4 evaluates it against the
 * account tolerance and stamps the {@code outcome} ({@code AUTO_CONFIRMED}/
 * {@code ROUTED_FOR_APPROVAL}) plus the {@code toleranceApplied} at the time,
 * and mirrors the outcome onto the PI's own {@code status}. Both are null on
 * a record that was computed but not yet evaluated (a partial-failure window
 * only — see {@code ToleranceEvaluationService}). {@code toleranceApplied} is
 * recorded because the account setting can change later: the reason a PI
 * routed must stay reproducible against the boundary that was actually used.
 *
 * {@code priceFileAsOfDate} is the date the price-file leg was resolved
 * against — the PO's generation date (see {@code
 * PurchaseOrderReconciliationView}), recorded here for auditability, same as
 * a PO line records its own {@code pricedAsOfDate}. {@code varianceBasis} is
 * recorded per-comparison so historical records stay interpretable if the
 * still-open basis question (see {@link VarianceBasis}) is ever answered
 * differently. {@code currencyMismatch} is the finding that the PI's
 * currency differs from the PO's — flagged, never auto-converted (no FX
 * here; that's Phase 2).
 */
@Entity
@Table(name = "reconciliations")
public class Reconciliation extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "proforma_invoice_id", nullable = false)
    private UUID proformaInvoiceId;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "variance_basis", nullable = false, length = 20)
    private VarianceBasis varianceBasis;

    @Column(name = "price_file_as_of_date")
    private LocalDate priceFileAsOfDate;

    @Column(name = "po_currency", length = 3)
    private String poCurrency;

    @Column(name = "pi_currency", nullable = false, length = 3)
    private String piCurrency;

    @Column(name = "currency_mismatch", nullable = false)
    private boolean currencyMismatch;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 30)
    private ReconciliationOutcome outcome;

    @Column(name = "tolerance_applied", precision = 5, scale = 2)
    private BigDecimal toleranceApplied;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Reconciliation() {
    }

    public Reconciliation(UUID proformaInvoiceId, UUID purchaseOrderId, VarianceBasis varianceBasis,
            LocalDate priceFileAsOfDate, String poCurrency, String piCurrency, boolean currencyMismatch) {
        this.proformaInvoiceId = proformaInvoiceId;
        this.purchaseOrderId = purchaseOrderId;
        this.varianceBasis = varianceBasis;
        this.priceFileAsOfDate = priceFileAsOfDate;
        this.poCurrency = poCurrency;
        this.piCurrency = piCurrency;
        this.currencyMismatch = currencyMismatch;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getProformaInvoiceId() {
        return proformaInvoiceId;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public VarianceBasis getVarianceBasis() {
        return varianceBasis;
    }

    public LocalDate getPriceFileAsOfDate() {
        return priceFileAsOfDate;
    }

    public String getPoCurrency() {
        return poCurrency;
    }

    public String getPiCurrency() {
        return piCurrency;
    }

    public boolean isCurrencyMismatch() {
        return currencyMismatch;
    }

    /**
     * Story 5.4's evaluation result — the outcome plus the exact tolerance
     * that produced it. Set once, when the comparison is evaluated; same
     * trust-the-caller convention as the rest of this codebase's mutators
     * ({@code ToleranceEvaluationService} is the only caller).
     */
    public void applyOutcome(ReconciliationOutcome outcome, BigDecimal toleranceApplied) {
        this.outcome = outcome;
        this.toleranceApplied = toleranceApplied;
    }

    /** Null until {@link #applyOutcome} runs (computed but not yet evaluated). */
    public ReconciliationOutcome getOutcome() {
        return outcome;
    }

    /** Null until {@link #applyOutcome} runs — the tolerance % applied at evaluation time. */
    public BigDecimal getToleranceApplied() {
        return toleranceApplied;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
