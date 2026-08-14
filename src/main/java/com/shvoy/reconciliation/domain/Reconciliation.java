package com.shvoy.reconciliation.domain;

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
 * This story <strong>computes and records</strong>; it makes no tolerance or
 * routing decision (5.4) — so there's no outcome/status field here yet. The
 * PI's own {@code status} (5.1) tracks the reconciliation lifecycle as 5.4/
 * 5.7 give it real transitions; this record is the raw, complete comparison
 * those stories read from.
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
