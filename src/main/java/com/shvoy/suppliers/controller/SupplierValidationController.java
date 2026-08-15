package com.shvoy.suppliers.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.suppliers.dto.BankDetailsRequest;
import com.shvoy.suppliers.dto.BankDetailsResponse;
import com.shvoy.suppliers.dto.ComplianceRequest;
import com.shvoy.suppliers.dto.SupplierResponse;
import com.shvoy.suppliers.dto.UnvalidateSupplierRequest;
import com.shvoy.suppliers.service.SupplierValidationService;

/**
 * The supplier-validation lifecycle endpoints (supplier remodel) — bank details,
 * compliance, and the explicit validate/un-validate approval. All {@code
 * FINANCE}/{@code ADMIN}: onboarding governance, and the full bank-details read
 * is the sensitive one that must never leak into the default supplier response.
 */
@RestController
@RequestMapping("/api/suppliers/{supplierId}")
class SupplierValidationController {

    private final SupplierValidationService validationService;

    SupplierValidationController(SupplierValidationService validationService) {
        this.validationService = validationService;
    }

    @PutMapping("/bank-details")
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
    SupplierResponse setBankDetails(@PathVariable UUID supplierId, @Valid @RequestBody BankDetailsRequest request) {
        return validationService.updateBankDetails(supplierId, request);
    }

    @GetMapping("/bank-details")
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
    BankDetailsResponse getBankDetails(@PathVariable UUID supplierId) {
        return validationService.getBankDetails(supplierId);
    }

    @PutMapping("/compliance")
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
    SupplierResponse setCompliance(@PathVariable UUID supplierId, @Valid @RequestBody ComplianceRequest request) {
        return validationService.setCompliance(supplierId, request);
    }

    @PostMapping("/validate")
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
    SupplierResponse validate(@PathVariable UUID supplierId) {
        return validationService.validate(supplierId);
    }

    @PostMapping("/unvalidate")
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
    SupplierResponse unvalidate(@PathVariable UUID supplierId, @Valid @RequestBody UnvalidateSupplierRequest request) {
        return validationService.unvalidate(supplierId, request);
    }
}
