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

import com.shvoy.ConflictException;
import com.shvoy.ErrorCode;
import com.shvoy.Money;
import com.shvoy.TenantScoped;

/**
 * One entry on the open-credit ledger (Story 6.7) — a shortfall, damage, or
 * agreed deduction the supplier owes back, recorded so it isn't forgotten and
 * so a supplier can't claim a deduction that was never agreed.
 *
 * <p><strong>Amount and cause are immutable after creation.</strong> There are
 * no setters for them, and no update endpoint — a correction is cancel-and-
 * relog. This is deliberate: the ledger's whole purpose is the rule "an invoice
 * claiming a credit is only accepted if it matches an open entry"; an editable
 * entry (nudged to match a claim) would defeat that control.
 *
 * <p>The only state changes are the guarded lifecycle transitions: {@link
 * #apply} (matched against an invoice — {@code OPEN → APPLIED}, once) and
 * {@link #cancel} (closed with a reason — {@code OPEN → CANCELLED}). Both throw
 * {@code CREDIT_NOT_OPEN} from any non-{@code OPEN} state, so an entry applies
 * exactly once and a closed one never reopens.
 *
 * <p>{@code targetInvoiceId} is the invoice this credit applies against —
 * nullable while {@code OPEN} awaiting its (usually future) invoice; set when
 * linked or when applied. {@code ncrReference} is a seam for the future
 * NCR-caused-credit link (see {@link CreditCause}), unused for now.
 */
@Entity
@Table(name = "credit_ledger_entries")
public class CreditLedgerEntry extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "amount_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "cause", nullable = false, length = 30)
    private CreditCause cause;

    @Column(name = "cause_detail", length = 1000)
    private String causeDetail;

    @Column(name = "ncr_reference", length = 100)
    private String ncrReference;

    @Column(name = "target_invoice_id")
    private UUID targetInvoiceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CreditLedgerStatus status;

    @Column(name = "closure_reason", length = 1000)
    private String closureReason;

    @Column(name = "logged_by", nullable = false)
    private UUID loggedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected CreditLedgerEntry() {
    }

    public CreditLedgerEntry(UUID purchaseOrderId, Money amount, CreditCause cause, String causeDetail,
            String ncrReference, UUID targetInvoiceId, UUID loggedBy) {
        this.purchaseOrderId = purchaseOrderId;
        this.amountAmount = amount.amount();
        this.currency = amount.currency();
        this.cause = cause;
        this.causeDetail = causeDetail;
        this.ncrReference = ncrReference;
        this.targetInvoiceId = targetInvoiceId;
        this.status = CreditLedgerStatus.OPEN;
        this.loggedBy = loggedBy;
        this.createdAt = Instant.now();
    }

    /** Link the invoice this credit is expected to apply against, while still OPEN (before it's actually applied). */
    public void linkTargetInvoice(UUID invoiceId) {
        requireOpen();
        this.targetInvoiceId = invoiceId;
        this.updatedAt = Instant.now();
    }

    /** {@code OPEN → APPLIED}, recording the consuming invoice — the once-only application (6.5 calls this on a match). */
    public void apply(UUID invoiceId) {
        requireOpen();
        this.status = CreditLedgerStatus.APPLIED;
        this.targetInvoiceId = invoiceId;
        this.updatedAt = Instant.now();
    }

    /** {@code OPEN → CANCELLED} with a required reason — closing a credit that will never apply. */
    public void cancel(String reason) {
        requireOpen();
        this.status = CreditLedgerStatus.CANCELLED;
        this.closureReason = reason;
        this.updatedAt = Instant.now();
    }

    private void requireOpen() {
        if (status != CreditLedgerStatus.OPEN) {
            throw new ConflictException(ErrorCode.CREDIT_NOT_OPEN,
                "Credit ledger entry is not OPEN (status " + status + ")");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public Money getAmount() {
        return new Money(amountAmount, currency);
    }

    public CreditCause getCause() {
        return cause;
    }

    public String getCauseDetail() {
        return causeDetail;
    }

    public String getNcrReference() {
        return ncrReference;
    }

    public UUID getTargetInvoiceId() {
        return targetInvoiceId;
    }

    public CreditLedgerStatus getStatus() {
        return status;
    }

    public String getClosureReason() {
        return closureReason;
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
