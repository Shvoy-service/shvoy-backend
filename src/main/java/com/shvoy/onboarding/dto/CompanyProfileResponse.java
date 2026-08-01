package com.shvoy.onboarding.dto;

import java.time.Instant;
import java.util.UUID;

public record CompanyProfileResponse(
    UUID id,
    String name,
    String registeredAddress,
    String country,
    String contactEmail,
    String contactPhone,
    String registrationNumber,
    Instant createdAt,
    Instant updatedAt
) {
}
