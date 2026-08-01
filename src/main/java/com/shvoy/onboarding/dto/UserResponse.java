package com.shvoy.onboarding.dto;

import java.util.UUID;

import com.shvoy.onboarding.domain.Role;

public record UserResponse(UUID id, String email, Role role) {
}
