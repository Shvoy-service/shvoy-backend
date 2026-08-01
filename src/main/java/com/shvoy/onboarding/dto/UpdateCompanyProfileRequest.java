package com.shvoy.onboarding.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Treated as a full representation of the editable profile (PUT semantics,
 * not a partial patch): a field omitted from the request clears the
 * corresponding column rather than leaving the previous value in place.
 */
public record UpdateCompanyProfileRequest(
    @Size(max = 500) String registeredAddress,
    @Size(max = 100) String country,
    @Email @Size(max = 255) String contactEmail,
    @Size(max = 50) String contactPhone,
    @Size(max = 100) String registrationNumber
) {
}
