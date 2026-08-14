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
 * One named member of a company's approver pool — the set of people eligible
 * for the price-increase sign-off gate (Story 5.6). Membership is
 * <strong>explicit</strong>, not merely derived from the {@link Role#APPROVER}
 * role: the business rule says "named individuals" and Screen 4 shows a
 * discrete list, so a company can have more APPROVER-role users than are
 * named in the pool. Holding the {@code APPROVER} role is a <em>constraint</em>
 * on membership (checked at add time), not what defines it — the role grants
 * "can approve at all", pool membership grants "eligible for this specific
 * gate".
 *
 * {@code userId} is a plain {@code UUID} referencing {@code users.id} — same
 * flat-column convention as everywhere else in this codebase, no JPA
 * relationship. A member's <em>eligibility</em> (active + still APPROVER) is
 * resolved against the live {@code User} at use time (see {@code
 * ApproverPoolService#resolveEligibleApprovers}), never snapshotted here, so
 * deactivating a user makes them ineligible without any pool mutation.
 * Immutable once created (add/remove, never edit), so there's no
 * {@code updated_at}.
 */
@Entity
@Table(name = "approver_pool_members")
public class ApproverPoolMember extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ApproverPoolMember() {
    }

    public ApproverPoolMember(UUID userId) {
        this.userId = userId;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
