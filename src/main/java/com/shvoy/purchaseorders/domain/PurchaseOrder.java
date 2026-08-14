package com.shvoy.purchaseorders.domain;

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
 * A purchase order to a single supplier — see Story 4.1. Data model only:
 * no create/edit endpoints exist yet (4.4), so this entity is currently
 * reachable only via direct repository access (tests) or a future story's
 * service layer, same shape as Supplier before 3.2 or Sku before 3.5.
 *
 * {@code poNumber} is assigned by {@link com.shvoy.purchaseorders.service.PoNumberGenerator}
 * at creation time — a per-company sequential "PO-0001" style reference,
 * not a UUID-derived code, matching what the wireframes show and what
 * users expect to reference a PO by. Unique per company (see V18), not
 * globally, since two different companies each having a "PO-0001" is
 * correct, not a collision.
 *
 * {@code createdBy} is a plain {@code UUID} referencing {@code users.id} —
 * no JPA relationship, matching this codebase's flat-column convention
 * throughout (see Supplier, Sku).
 */
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "po_number", nullable = false, length = 50)
    private String poNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseOrderStatus status;

    @Column(name = "requested_etd")
    private LocalDate requestedEtd;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "order_total_amount", precision = 19, scale = 2)
    private BigDecimal orderTotalAmount;

    @Column(length = 3)
    private String currency;

    @Column(name = "deposit_amount", precision = 19, scale = 2)
    private BigDecimal depositAmount;

    @Column(name = "balance_amount", precision = 19, scale = 2)
    private BigDecimal balanceAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected PurchaseOrder() {
    }

    public PurchaseOrder(UUID supplierId, String poNumber, UUID createdBy) {
        this.supplierId = supplierId;
        this.poNumber = poNumber;
        this.status = PurchaseOrderStatus.DRAFT;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /**
     * The order total (Story 4.3) — the sum of this PO's already-rounded
     * 2dp line totals, never a rounded sum of unrounded line values (see
     * docs/CONTRACT.md's Money section; {@code PurchaseOrderTotalsService}
     * does the actual summing, this just stores the result). Null when the
     * PO has no priced lines yet — never a fabricated zero.
     */
    public void applyOrderTotal(Money orderTotal) {
        this.orderTotalAmount = orderTotal == null ? null : orderTotal.amount();
        this.currency = orderTotal == null ? null : orderTotal.currency();
        this.updatedAt = Instant.now();
    }

    /**
     * The supplier's payment-terms split (3.3) of this PO's order total —
     * {@code deposit.plus(balance)} always equals {@link #getOrderTotal}
     * exactly, since {@code PaymentTerms#split} guarantees it. Cleared
     * (see {@link #clearDepositBalanceSplit}), not left stale, whenever
     * there's no order total to split against, or the supplier has no
     * payment terms configured.
     */
    public void applyDepositBalanceSplit(Money deposit, Money balance) {
        this.depositAmount = deposit.amount();
        this.balanceAmount = balance.amount();
        this.updatedAt = Instant.now();
    }

    public void clearDepositBalanceSplit() {
        this.depositAmount = null;
        this.balanceAmount = null;
        this.updatedAt = Instant.now();
    }

    /**
     * Story 4.4. Past-date rejection is a service-layer concern (it needs
     * "today", which is a policy the entity shouldn't own) — this just
     * stores whatever it's given, including {@code null} to clear it.
     */
    public void setRequestedEtd(LocalDate requestedEtd) {
        this.requestedEtd = requestedEtd;
        this.updatedAt = Instant.now();
    }

    /**
     * Story 4.4's soft-delete for a draft — {@link PurchaseOrderStatus#CANCELLED}
     * is terminal, never reached from {@code GENERATED}/{@code SENT} (the
     * service layer enforces the DRAFT-only precondition via
     * {@link #isEditable} before calling this; this method itself doesn't
     * re-check, same trust-the-caller convention as {@code Supplier#deactivate}).
     */
    public void cancel() {
        this.status = PurchaseOrderStatus.CANCELLED;
        this.updatedAt = Instant.now();
    }

    /**
     * Story 4.4's status guard: every mutation (lines, ETD, cancel) is only
     * permitted while the PO is still a DRAFT — once {@code GENERATED}/
     * {@code SENT}/{@code CANCELLED}, it's frozen. Checked by the service
     * layer before each mutation, which throws {@code PO_NOT_EDITABLE}
     * rather than silently no-opping.
     */
    public boolean isEditable() {
        return status == PurchaseOrderStatus.DRAFT;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public String getPoNumber() {
        return poNumber;
    }

    public PurchaseOrderStatus getStatus() {
        return status;
    }

    public LocalDate getRequestedEtd() {
        return requestedEtd;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    /** Null until at least one line is priced — see {@link #applyOrderTotal}. */
    public Money getOrderTotal() {
        return orderTotalAmount == null ? null : new Money(orderTotalAmount, currency);
    }

    /** Null until {@link #applyDepositBalanceSplit} runs. */
    public Money getDeposit() {
        return depositAmount == null ? null : new Money(depositAmount, currency);
    }

    /** Null until {@link #applyDepositBalanceSplit} runs. */
    public Money getBalance() {
        return balanceAmount == null ? null : new Money(balanceAmount, currency);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
