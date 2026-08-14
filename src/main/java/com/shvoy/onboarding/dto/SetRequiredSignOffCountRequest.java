package com.shvoy.onboarding.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Sets the required sign-off count (the "N" in N-of-pool) — Story 5.6. A
 * minimum of 1 is enforced here; a count of 1 is a legitimate small-company
 * configuration, though it defeats the multi-party point of the gate (flagged,
 * not blocked). The upper bound — it must not exceed the active pool size — is
 * a service-level check against live data (`APPROVER_COUNT_EXCEEDS_POOL`),
 * since bean validation can't see the pool.
 */
public record SetRequiredSignOffCountRequest(
    @NotNull @Min(1) Integer requiredSignOffCount
) {
}
