package com.shvoy.suppliers.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.shvoy.Money;
import com.shvoy.TenantScoped;

/**
 * A supplier's payment terms — see Story 3.3. Keyed directly by
 * {@code supplier_id} (no separate generated id, no JPA relationship to
 * Supplier) rather than a derived repository finder, so
 * {@code PaymentTermsRepository.findById(supplierId)} stays a plain
 * inherited JpaRepository method — see PaymentTermsRepository's Javadoc for
 * why that matters at startup.
 *
 * Only the deposit percentage is stored; balance is always {@code 100 -
 * deposit} (see {@link #getBalancePercentage}), never an independently
 * stored/validated field — this makes the two out of sync impossible by
 * construction rather than by a sum-to-100 validation rule.
 */
@Entity
@Table(name = "payment_terms")
public class PaymentTerms extends TenantScoped {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    @Id
    @Column(name = "supplier_id")
    private UUID supplierId;

    @Column(name = "deposit_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal depositPercentage;

    @Enumerated(EnumType.STRING)
    @Column(name = "anchor_event", nullable = false, length = 20)
    private AnchorEvent anchorEvent;

    @Column(name = "days_offset", nullable = false)
    private int daysOffset;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected PaymentTerms() {
    }

    public PaymentTerms(UUID supplierId, BigDecimal depositPercentage, AnchorEvent anchorEvent, int daysOffset) {
        this.supplierId = supplierId;
        this.depositPercentage = depositPercentage;
        this.anchorEvent = anchorEvent;
        this.daysOffset = daysOffset;
        this.createdAt = Instant.now();
    }

    public void update(BigDecimal depositPercentage, AnchorEvent anchorEvent, int daysOffset) {
        this.depositPercentage = depositPercentage;
        this.anchorEvent = anchorEvent;
        this.daysOffset = daysOffset;
        this.updatedAt = Instant.now();
    }

    /**
     * The allocation-remainder rule (Story 3.3): the deposit is rounded
     * (HALF_EVEN at scale 2, via {@link Money#multiply}, matching
     * docs/CONTRACT.md's Money rounding rule) and the balance absorbs
     * whatever's left — {@code total.minus(deposit)}, not independently
     * rounded. This guarantees {@code deposit.plus(balance)} always equals
     * {@code total} exactly, with any odd penny falling on the balance.
     * Feature 7 calls this against a real order total; nothing here is
     * wired to an endpoint yet.
     */
    public PaymentSplit split(Money total) {
        BigDecimal depositFraction = depositPercentage.divide(ONE_HUNDRED);
        Money deposit = total.multiply(depositFraction);
        Money balance = total.minus(deposit);
        return new PaymentSplit(deposit, balance);
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public BigDecimal getDepositPercentage() {
        return depositPercentage;
    }

    public BigDecimal getBalancePercentage() {
        return ONE_HUNDRED.subtract(depositPercentage);
    }

    public AnchorEvent getAnchorEvent() {
        return anchorEvent;
    }

    public int getDaysOffset() {
        return daysOffset;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
