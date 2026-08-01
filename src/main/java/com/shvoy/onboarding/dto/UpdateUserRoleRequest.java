package com.shvoy.onboarding.dto;

import jakarta.validation.constraints.NotNull;

import com.shvoy.onboarding.domain.Role;

public record UpdateUserRoleRequest(@NotNull Role role) {
}
