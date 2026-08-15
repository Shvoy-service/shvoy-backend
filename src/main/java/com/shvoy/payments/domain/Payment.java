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
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Payment() {
    }

    public Payment(UUID purchaseOrderId, PaymentType type, Money amount) {
        this.purchaseOrderId = purchaseOrderId;
        this.type = type;
        this.amountAmount = amount.amount();
        this.currency = amount.currency();
        this.status = PaymentStatus.PENDING;
        this.dueDate = null;
        this.createdAt = Instant.now();
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

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
