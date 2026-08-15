package com.shvoy.suppliers.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Shared by create and update — same field set either way, so a second,
 * near-identical DTO would just be duplication. Treated as a full
 * representation for updates (PUT semantics, not a partial patch), same
 * convention as UpdateCompanyProfileRequest: an omitted optional field
 * clears the corresponding column rather than leaving the previous value.
 */
public record SupplierRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 100) String country,
    @Email @Size(max = 255) String contactEmail,
    // The supplier's default incoterm code, pre-filled onto new POs (PO-issuance gate). Optional.
    @Size(max = 10) String defaultIncoterms
) {
}
