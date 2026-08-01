package com.shvoy.onboarding.dto;

import java.util.UUID;

import com.shvoy.onboarding.domain.Role;
import com.shvoy.onboarding.domain.UserStatus;

public record ActivateAccountResponse(UUID id, String email, Role role, UserStatus status) {
}
