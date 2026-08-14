package com.shvoy.onboarding.domain;

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
 * A company's approver-pool settings — currently just the required sign-off
 * count, the "N" in the 2-of-N gate (Story 5.6). One row per company; when
 * absent, {@code ApproverPoolService.DEFAULT_REQUIRED_SIGN_OFF_COUNT} (2,
 * matching the business rule's 2-of-3 example) applies.
 *
 * Kept as its own one-row-per-company table rather than a column on {@code
 * Company}, mirroring the tolerance setting (5.4): it keeps the core company
 * table lean and groups this governance knob with the pool it belongs to.
 */
@Entity
@Table(name = "approver_pool_settings")
public class ApproverPoolSettings extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "required_sign_off_count", nullable = false)
    private int requiredSignOffCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ApproverPoolSettings() {
    }

    public ApproverPoolSettings(int requiredSignOffCount) {
        this.requiredSignOffCount = requiredSignOffCount;
        this.createdAt = Instant.now();
    }

    public void updateRequiredSignOffCount(int requiredSignOffCount) {
        this.requiredSignOffCount = requiredSignOffCount;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public int getRequiredSignOffCount() {
        return requiredSignOffCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
