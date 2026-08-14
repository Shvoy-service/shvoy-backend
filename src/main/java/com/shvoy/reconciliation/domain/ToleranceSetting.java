package com.shvoy.reconciliation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.shvoy.TenantScoped;

/**
 * The reconciliation tolerance for a company (Story 5.4). Per the Product
 * Owner's answer, tolerance is configurable <strong>per account (company),
 * one global setting</strong> — not per-supplier, not per-SKU (deferred
 * beyond MVP). One row per company; when absent, {@code
 * ToleranceService.DEFAULT_TOLERANCE} (~2%) applies, so reconciliation works
 * before anyone configures anything.
 *
 * {@code tolerancePercentage} is a percentage in the same units as a
 * variance % (e.g. {@code 2.00} means 2%), so the tolerance comparison is a
 * direct like-for-like against the 5.3 variance with no unit conversion.
 *
 * A future move to per-supplier tolerance is an extension, not a redesign:
 * callers resolve the effective tolerance through the single {@code
 * ToleranceService#resolveEffectiveTolerance} lookup, which today always
 * returns this account setting and could later take a supplier into account
 * without changing its call sites.
 */
@Entity
@Table(name = "tolerance_settings")
public class ToleranceSetting extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tolerance_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal tolerancePercentage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ToleranceSetting() {
    }

    public ToleranceSetting(BigDecimal tolerancePercentage) {
        this.tolerancePercentage = tolerancePercentage;
        this.createdAt = Instant.now();
    }

    public void updateTolerancePercentage(BigDecimal tolerancePercentage) {
        this.tolerancePercentage = tolerancePercentage;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public BigDecimal getTolerancePercentage() {
        return tolerancePercentage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
