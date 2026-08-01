package com.shvoy.onboarding.dto;

import java.util.UUID;

public record RegisterCompanyResponse(UUID companyId, UUID userId, boolean verificationRequired) {
}
