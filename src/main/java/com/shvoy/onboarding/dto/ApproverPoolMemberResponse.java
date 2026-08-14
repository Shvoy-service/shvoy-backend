package com.shvoy.onboarding.dto;

import java.util.UUID;

import com.shvoy.onboarding.domain.Role;
import com.shvoy.onboarding.domain.UserStatus;

/**
 * One member of the approver pool, joined to the live user so the admin sees
 * who it is and whether they're still eligible (Story 5.6). {@code eligible}
 * is {@code true} only when the user is currently {@code ACTIVE} and still
 * holds the {@code APPROVER} role — a member who was later deactivated or had
 * their role changed stays in the pool (no silent mutation) but shows as
 * ineligible, and is excluded from the set the 5.5 gate actually routes to.
 */
public record ApproverPoolMemberResponse(
    UUID userId,
    String email,
    Role role,
    UserStatus status,
    boolean eligible
) {
}
