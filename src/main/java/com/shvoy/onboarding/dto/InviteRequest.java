package com.shvoy.onboarding.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.shvoy.onboarding.domain.Role;

public record InviteRequest(
    @NotBlank @Email String email,
    @NotNull Role role
) {
}
