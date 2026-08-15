package com.shvoy.suppliers.domain;

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

import com.shvoy.Money;
import com.shvoy.TenantScoped;

/**
 * A payment-terms record (supplier remodel) — no longer keyed by supplier (a
 * supplier can now hold several over time: a current, a target, and retained
 * history). A supplier references its {@code current}/{@code target} term by id.
 *
 * <p>Carries the typed model: {@link PaymentTermsType}, a nullable {@code
 * depositPct} (populated only for {@code DEPOSIT_BALANCE}, 1dp), a five-value
 * {@code anchorDateType}, and a signed {@code daysFromAnchor}. Type-consistency
 * (deposit present iff DEPOSIT_BALANCE, statement anchor only for ROLLING) is
 * enforced at the service boundary, so the persisted record is always coherent.
 */
@Entity
@Table(name = "payment_terms")
public class PaymentTerms extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "terms_type", nullable = false, length = 20)
    private PaymentTermsType termsType;

    @Column(name = "deposit_pct", precision = 4, scale = 1)
    private BigDecimal depositPct;

    @Enumerated(EnumType.STRING)
    @Column(name = "anchor_date_type", nullable = false, length = 20)
    private AnchorEvent anchorDateType;

    @Column(name = "days_from_anchor", nullable = false)
    private int daysFromAnchor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected PaymentTerms() {
    }

    public PaymentTerms(UUID supplierId, PaymentTermsType termsType, BigDecimal depositPct, AnchorEvent anchorDateType,
            int daysFromAnchor) {
        this.supplierId = supplierId;
        this.termsType = termsType;
        this.depositPct = depositPct;
        this.anchorDateType = anchorDateType;
        this.daysFromAnchor = daysFromAnchor;
        this.createdAt = Instant.now();
    }

    /** In-place edit of a term slot (current or target) — full-replace PUT semantics. */
    public void update(PaymentTermsType termsType, BigDecimal depositPct, AnchorEvent anchorDateType,
            int daysFromAnchor) {
        this.termsType = termsType;
        this.depositPct = depositPct;
        this.anchorDateType = anchorDateType;
        this.daysFromAnchor = daysFromAnchor;
        this.updatedAt = Instant.now();
    }

    /**
     * The deposit/balance split of {@code total} (Story 4.3's rule, preserved):
     * the deposit is rounded HALF_EVEN at scale 2 and the balance absorbs the
     * remainder, so {@code deposit + balance == total} exactly. A null deposit
     * ({@code ZERO_DEPOSIT}/{@code ROLLING}) means a zero deposit and a
     * full-total balance — rolling's per-PO behaviour is deferred to the 6.5
     * re-spec; representing it as a single balance keeps 6.1 unchanged.
     */
    public PaymentSplit split(Money total) {
        if (depositPct == null) {
            return new PaymentSplit(Money.zero(total.currency()), total);
        }
        Money deposit = total.multiply(depositPct.divide(BigDecimal.valueOf(100)));
        return new PaymentSplit(deposit, total.minus(deposit));
    }

    public UUID getId() {
        return id;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public PaymentTermsType getTermsType() {
        return termsType;
    }

    public BigDecimal getDepositPct() {
        return depositPct;
    }

    public AnchorEvent getAnchorDateType() {
        return anchorDateType;
    }

    public int getDaysFromAnchor() {
        return daysFromAnchor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
