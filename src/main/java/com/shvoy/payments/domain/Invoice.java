package com.shvoy.payments.domain;

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

import com.shvoy.Money;
import com.shvoy.TenantScoped;

/**
 * The supplier's final invoice against a PO — the fourth leg of the three-way
 * match (PO = PI = GRN vs <strong>invoice</strong>), see Story 6.4. Same
 * "record faithfully, judge later" philosophy as PI logging (5.2): the amount,
 * currency, and date are stored as the supplier stated them, even when they
 * disagree with the PO/PI — that disagreement is the match's job (6.5) to
 * catch, not entry validation's.
 *
 * <strong>Header-level (MVP).</strong> Just the reference, total amount, date,
 * and an optional claimed credit — no line items. Screen 6 shows a single
 * "Invoice $xx" column, and line-level price scrutiny already happened at PI
 * reconciliation (Feature 5). Modelled so lines <em>could</em> be added later
 * (the AI extraction layer will read full documents) but not built
 * speculatively. (Flagged assumption — see docs/CONTRACT.md.)
 *
 * <strong>Cardinality: one active invoice per PO</strong> — a correction
 * supersedes the prior (kept for audit), same as a PI. Whether suppliers ever
 * invoice a PO in <em>parts</em> (partial invoicing) is a flagged Product Owner
 * question; if yes this becomes invoice-per-payment.
 *
 * <strong>Claimed credit</strong> ({@code claimedCreditAmount} + optional
 * {@code claimedCreditReference}) is what the supplier <em>says</em> they've
 * deducted — captured faithfully; validating it against an open ledger credit
 * is 6.7's rule applied at match time (6.5), not here. Its currency is the
 * invoice's.
 */
@Entity
@Table(name = "invoices")
public class Invoice extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "invoice_reference", nullable = false, length = 100)
    private String invoiceReference;

    @Column(name = "amount_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "claimed_credit_amount", precision = 19, scale = 2)
    private BigDecimal claimedCreditAmount;

    @Column(name = "claimed_credit_reference", length = 100)
    private String claimedCreditReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceStatus status;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "logged_by", nullable = false)
    private UUID loggedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Invoice() {
    }

    public Invoice(UUID purchaseOrderId, String invoiceReference, Money amount, LocalDate invoiceDate,
            BigDecimal claimedCreditAmount, String claimedCreditReference, UUID loggedBy) {
        this.purchaseOrderId = purchaseOrderId;
        this.invoiceReference = invoiceReference;
        this.amountAmount = amount.amount();
        this.currency = amount.currency();
        this.invoiceDate = invoiceDate;
        this.claimedCreditAmount = claimedCreditAmount;
        this.claimedCreditReference = claimedCreditReference;
        this.status = InvoiceStatus.LOGGED;
        this.active = true;
        this.loggedBy = loggedBy;
        this.createdAt = Instant.now();
    }

    /** Flips this invoice inactive when a correction is logged against the same PO — same convention as {@code ProformaInvoice#supersede}. */
    public void supersede() {
        this.status = InvoiceStatus.SUPERSEDED;
        this.active = false;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public String getInvoiceReference() {
        return invoiceReference;
    }

    public Money getAmount() {
        return new Money(amountAmount, currency);
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    /** What the supplier claims to have deducted, in the invoice's currency; null when none is claimed. */
    public Money getClaimedCredit() {
        return claimedCreditAmount == null ? null : new Money(claimedCreditAmount, currency);
    }

    public String getClaimedCreditReference() {
        return claimedCreditReference;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return active;
    }

    public UUID getLoggedBy() {
        return loggedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
