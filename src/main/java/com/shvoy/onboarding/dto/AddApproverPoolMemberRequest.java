package com.shvoy.onboarding.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Adds a named user to the approver pool (Story 5.6). */
public record AddApproverPoolMemberRequest(
    @NotNull UUID userId
) {
}
