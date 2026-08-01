package com.shvoy.onboarding.dto;

import com.shvoy.onboarding.domain.Role;
import com.shvoy.onboarding.domain.UserStatus;

public record InviteResponse(String email, Role role, UserStatus status) {
}
