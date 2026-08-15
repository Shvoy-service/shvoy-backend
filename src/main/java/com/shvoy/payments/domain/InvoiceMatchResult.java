package com.shvoy.payments.domain;

import java.math.BigDecimal;
import java.time.Instant;
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
 * The durable outcome of matching one invoice (Story 6.5 re-spec) — one current
 * row per invoice, replaced on each re-evaluation. It records what the match
 * decided independent of whether a payment carried the verdict, which is exactly
 * what a <strong>rolling</strong> supplier needs: no per-PO payment transitions
 * happen, but the per-shipment/invoice verdicts are recorded here for the
 * statement view (the next-but-one story) to reconcile against. For
 * deposit/balance suppliers it's the same record, alongside the payment gating.
 *
 * <p>{@code positionMatched} flags an {@code AMOUNT} invoice that reconciled only
 * loosely (fit within the unclaimed received value, not an exact coverage match)
 * — the weakest signal, surfaced so Finance sees it.
 */
@Entity
@Table(name = "invoice_match_results")
public class InvoiceMatchResult extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "covers_type", nullable = false, length = 20)
    private InvoiceCoversType coversType;

    @Column(name = "terms_type", length = 20)
    private String termsType;

    @Column(name = "passed", nullable = false)
    private boolean passed;

    @Column(name = "position_matched", nullable = false)
    private boolean positionMatched;

    @Column(name = "expected_amount", precision = 19, scale = 2)
    private BigDecimal expectedAmount;

    @Column(name = "invoice_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal invoiceAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "detail", length = 2000)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_applied", nullable = false, length = 20)
    private MatchPolicy policyApplied;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    protected InvoiceMatchResult() {
    }

    public InvoiceMatchResult(UUID purchaseOrderId, UUID invoiceId, InvoiceCoversType coversType, String termsType,
            boolean passed, boolean positionMatched, BigDecimal expectedAmount, BigDecimal invoiceAmount,
            String currency, String detail, MatchPolicy policyApplied) {
        this.purchaseOrderId = purchaseOrderId;
        this.invoiceId = invoiceId;
        set(coversType, termsType, passed, positionMatched, expectedAmount, invoiceAmount, currency, detail, policyApplied);
    }

    /** Re-evaluation replaces the recorded outcome in place (one current row per invoice). */
    public void update(InvoiceCoversType coversType, String termsType, boolean passed, boolean positionMatched,
            BigDecimal expectedAmount, BigDecimal invoiceAmount, String currency, String detail, MatchPolicy policyApplied) {
        set(coversType, termsType, passed, positionMatched, expectedAmount, invoiceAmount, currency, detail, policyApplied);
    }

    private void set(InvoiceCoversType coversType, String termsType, boolean passed, boolean positionMatched,
            BigDecimal expectedAmount, BigDecimal invoiceAmount, String currency, String detail, MatchPolicy policyApplied) {
        this.coversType = coversType;
        this.termsType = termsType;
        this.passed = passed;
        this.positionMatched = positionMatched;
        this.expectedAmount = expectedAmount;
        this.invoiceAmount = invoiceAmount;
        this.currency = currency;
        this.detail = detail;
        this.policyApplied = policyApplied;
        this.evaluatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public InvoiceCoversType getCoversType() {
        return coversType;
    }

    public String getTermsType() {
        return termsType;
    }

    public boolean isPassed() {
        return passed;
    }

    public boolean isPositionMatched() {
        return positionMatched;
    }

    public BigDecimal getExpectedAmount() {
        return expectedAmount;
    }

    public BigDecimal getInvoiceAmount() {
        return invoiceAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDetail() {
        return detail;
    }

    public MatchPolicy getPolicyApplied() {
        return policyApplied;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }
}
