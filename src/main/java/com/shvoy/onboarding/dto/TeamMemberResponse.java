package com.shvoy.onboarding.dto;

import java.util.UUID;

import com.shvoy.onboarding.domain.Role;
import com.shvoy.onboarding.domain.UserStatus;

public record TeamMemberResponse(UUID id, String email, Role role, UserStatus status) {
}
