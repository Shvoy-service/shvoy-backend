package com.shvoy.onboarding.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterCompanyRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(max = 255) String companyName
) {
}
