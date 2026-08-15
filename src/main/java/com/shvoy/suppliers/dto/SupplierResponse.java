package com.shvoy.suppliers.dto;

import java.time.Instant;
import java.util.UUID;

import com.shvoy.suppliers.domain.ComplianceStatus;
import com.shvoy.suppliers.domain.SupplierStatus;
import com.shvoy.suppliers.domain.SupplierValidationStatus;

/**
 * The default supplier read (supplier remodel). Carries the validation lifecycle
 * and compliance, and the bank account in **masked** form only (last 4) — full
 * bank details are a separate FINANCE/ADMIN read ({@code BankDetailsResponse}),
 * never in the default response. {@code readyForValidation} is the derived
 * "required fields present" signal a human then approves against.
 */
public record SupplierResponse(
    UUID id,
    String name,
    SupplierStatus status,
    String country,
    String contactEmail,
    SupplierValidationStatus validationStatus,
    boolean readyForValidation,
    ComplianceStatus complianceStatus,
    String bankAccountNumberMasked,
    boolean bankDetailsPresent,
    Instant createdAt,
    Instant updatedAt
) {
}
