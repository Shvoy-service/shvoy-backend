package com.shvoy.reconciliation.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.reconciliation.dto.ToleranceSettingResponse;
import com.shvoy.reconciliation.dto.UpdateToleranceSettingRequest;
import com.shvoy.reconciliation.service.ToleranceService;

/**
 * Story 5.4 — the account's one configurable reconciliation tolerance. No
 * {@code {companyId}} path segment — the caller's company comes from {@code
 * TenantContext}, same as every other controller. Reading is open to any
 * authenticated company user; changing it is ADMIN-only, an account-wide
 * policy setting.
 */
@RestController
@RequestMapping("/api/reconciliation/tolerance")
class ToleranceSettingController {

    private final ToleranceService toleranceService;

    ToleranceSettingController(ToleranceService toleranceService) {
        this.toleranceService = toleranceService;
    }

    @GetMapping
    ToleranceSettingResponse get() {
        return toleranceService.get();
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    ToleranceSettingResponse update(@Valid @RequestBody UpdateToleranceSettingRequest request) {
        return toleranceService.update(request);
    }
}
