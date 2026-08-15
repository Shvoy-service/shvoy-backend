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
import com.shvoy.suppliers.domain.AnchorEvent;

/**
 * A deposit or balance payment owed against a PO — see Story 6.1. The
 * foundation the payment queue (6.3), due-date calculation (6.2), three-way
 * match (6.5), and release flow (6.8) all operate on. Data model only: no
 * calculation or lifecycle enforcement yet.
 *
 * {@code purchaseOrderId} is a plain {@code UUID} referencing
 * {@code purchase_orders.id} — same flat-column convention as everywhere else
 * in this codebase, no JPA relationship.
 *
 * <strong>Amount is a snapshot.</strong> The 4.3 deposit/balance split
 * (deposit rounded HALF_EVEN, balance absorbs the remainder, so {@code
 * deposit + balance == orderTotal} exactly) is copied onto the payment at
 * creation, never recomputed from the PO on read — the same snapshot principle
 * as PO line prices. The obligation is fixed at generation; it shouldn't
 * silently drift.
 *
 * <strong>Due date is deliberately nullable.</strong> A balance payment's due
 * date may be unknowable until its anchor event (BL date, arrival) occurs in
 * Feature 7, so a payment can exist with a fixed amount but a pending due
 * date. Calculating due dates is 6.2's job; this model just holds the field.
 *
 * <strong>No reference to supplier terms is stored here.</strong> The payment
 * is term-agnostic — it holds amounts and dates, not terms. Which terms drive
 * the due-date calculation (including the still-open dual-term-supplier
 * question) stays isolated in 6.2, the same discipline as the tolerance and
 * carton rules.
 */
@Entity
@Table(name = "payments")
public class Payment extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private PaymentType type;

    @Column(name = "amount_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "anchor_event", length = 20)
    private AnchorEvent anchorEvent;

    @Column(name = "days_offset")
    private Integer daysOffset;

    @Column(name = "anchor_date_applied")
    private LocalDate anchorDateApplied;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "match_detail", length = 2000)
    private String matchDetail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Payment() {
    }

    private Payment(UUID purchaseOrderId, PaymentType type, Money amount) {
        this.purchaseOrderId = purchaseOrderId;
        this.type = type;
        this.amountAmount = amount.amount();
        this.currency = amount.currency();
        this.status = PaymentStatus.PENDING;
        this.createdAt = Instant.now();
    }

    /**
     * A deposit payment (Story 6.2) — born with its due date, the PO
     * generation date (the MVP default: deposits are effectively "due now" to
     * unlock production, not anchored to a shipment event). No anchor terms.
     */
    public static Payment deposit(UUID purchaseOrderId, Money amount, LocalDate generationDate) {
        Payment payment = new Payment(purchaseOrderId, PaymentType.DEPOSIT, amount);
        payment.dueDate = generationDate;
        return payment;
    }

    /**
     * A balance payment (Story 6.2) — born <em>without</em> a due date, holding
     * the terms snapshotted at generation ({@code anchorEvent} + signed {@code
     * daysOffset}) so its due date can be computed once the anchor event's date
     * becomes known (Feature 7). Both are null when the supplier had no terms
     * configured, in which case the due date has no anchor and stays null.
     */
    public static Payment balance(UUID purchaseOrderId, Money amount, AnchorEvent anchorEvent, Integer daysOffset) {
        Payment payment = new Payment(purchaseOrderId, PaymentType.BALANCE, amount);
        payment.anchorEvent = anchorEvent;
        payment.daysOffset = daysOffset;
        return payment;
    }

    /**
     * Sets (or revises) the calculated due date from a now-known anchor date —
     * Story 6.2's re-entrant seam. Due date = {@code anchorDate + daysOffset};
     * {@code anchorDate} is retained so the derivation stays explicable. Only
     * valid on a balance with snapshotted terms; the caller
     * ({@code PaymentDueDateService}) only invokes it on matching balances.
     */
    public void applyCalculatedDueDate(LocalDate anchorDate) {
        this.anchorDateApplied = anchorDate;
        this.dueDate = anchorDate.plusDays(daysOffset);
        this.updatedAt = Instant.now();
    }

    /**
     * Whether the three-way match (Story 6.5) may set this payment's status. The
     * match owns only the <em>automatic</em> states ({@code PENDING} /
     * {@code BLOCKED} / {@code READY_TO_PAY}); it must never override a human
     * decision ({@code PAID} released or {@code ON_HOLD} parked, 6.8). A blocked
     * match that later passes still can't un-hold a payment a person parked.
     */
    public boolean isMatchMutable() {
        return status == PaymentStatus.PENDING
            || status == PaymentStatus.BLOCKED
            || status == PaymentStatus.READY_TO_PAY;
    }

    /** The match passed → READY_TO_PAY, clearing any prior failure detail (Story 6.5). Guarded by {@link #isMatchMutable()}. */
    public void markMatchPassed() {
        this.status = PaymentStatus.READY_TO_PAY;
        this.matchDetail = null;
        this.updatedAt = Instant.now();
    }

    /** The match failed → BLOCKED, recording which leg disagreed (Story 6.5). Guarded by {@link #isMatchMutable()}. */
    public void markMatchBlocked(String detail) {
        this.status = PaymentStatus.BLOCKED;
        this.matchDetail = detail;
        this.updatedAt = Instant.now();
    }

    /**
     * Not yet matchable — a leg is still missing (Story 6.5). Kept distinct from
     * {@code BLOCKED}: a missing invoice/GRN is "awaiting X", not a mismatch, and
     * the queue should say so honestly. Returns the payment to {@code PENDING}
     * with the awaiting reason in the detail.
     */
    public void markAwaiting(String detail) {
        this.status = PaymentStatus.PENDING;
        this.matchDetail = detail;
        this.updatedAt = Instant.now();
    }

    /** A deposit made payable without the match, per the per-type gate policy (Story 6.5). */
    public void markPayableWithoutMatch() {
        this.status = PaymentStatus.READY_TO_PAY;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public PaymentType getType() {
        return type;
    }

    /** The snapshotted obligation amount — see the class Javadoc. */
    public Money getAmount() {
        return new Money(amountAmount, currency);
    }

    /** Null until 6.2 calculates it (or, for a balance, until Feature 7's anchor event). */
    public LocalDate getDueDate() {
        return dueDate;
    }

    /** The event a balance's due date is anchored to (snapshotted at generation); null for deposits / no-terms. */
    public AnchorEvent getAnchorEvent() {
        return anchorEvent;
    }

    /** The signed days offset from the anchor date (snapshotted at generation); null for deposits / no-terms. */
    public Integer getDaysOffset() {
        return daysOffset;
    }

    /** The anchor date the current due date was derived from — for auditability; null until an anchor date is applied. */
    public LocalDate getAnchorDateApplied() {
        return anchorDateApplied;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getMatchDetail() {
        return matchDetail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
