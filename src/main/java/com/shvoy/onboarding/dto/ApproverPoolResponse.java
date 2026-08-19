package com.shvoy.onboarding.dto;

import java.util.List;

/**
 * A company's approver pool configuration (Story 5.6): the required sign-off
 * count (the "N" in 2-of-N) and the named members. {@code eligibleMemberCount}
 * is the number of members who are currently active APPROVERs — the size the
 * required count is validated against, and what actually matters for whether
 * the gate can be met. {@code usingDefaultRequiredCount} flags that no count
 * has been configured and the built-in default is in effect.
 */
@org.springframework.modulith.NamedInterface("approver-pool")
public record ApproverPoolResponse(
    int requiredSignOffCount,
    boolean usingDefaultRequiredCount,
    int eligibleMemberCount,
    List<ApproverPoolMemberResponse> members
) {
}
