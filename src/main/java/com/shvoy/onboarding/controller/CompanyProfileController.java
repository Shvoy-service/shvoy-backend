package com.shvoy.onboarding.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.onboarding.dto.CompanyProfileResponse;
import com.shvoy.onboarding.dto.UpdateCompanyProfileRequest;
import com.shvoy.onboarding.service.CompanyProfileService;

@RestController
@RequestMapping("/api/onboarding/company/{companyId}/profile")
class CompanyProfileController {

    private final CompanyProfileService companyProfileService;

    CompanyProfileController(CompanyProfileService companyProfileService) {
        this.companyProfileService = companyProfileService;
    }

    @GetMapping
    CompanyProfileResponse getProfile(@PathVariable UUID companyId) {
        return companyProfileService.getProfile(companyId);
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    CompanyProfileResponse updateProfile(@PathVariable UUID companyId,
            @Valid @RequestBody UpdateCompanyProfileRequest request) {
        return companyProfileService.updateProfile(companyId, request);
    }
}
