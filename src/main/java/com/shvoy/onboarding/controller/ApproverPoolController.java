package com.shvoy.onboarding.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.onboarding.dto.AddApproverPoolMemberRequest;
import com.shvoy.onboarding.dto.ApproverPoolResponse;
import com.shvoy.onboarding.dto.SetRequiredSignOffCountRequest;
import com.shvoy.onboarding.service.ApproverPoolService;

/**
 * Story 5.6 — configure the approver pool and the required sign-off count.
 * Follows the onboarding company-config convention (a {@code {companyId}}
 * path segment guarded against {@code TenantContext}), same as {@code
 * TeamController}/{@code CompanyProfileController}.
 *
 * Configuring the pool is ADMIN-only — it's the governance control that
 * determines who can authorise price increases, so it belongs with the role
 * that manages users/roles, not PURCHASING (who would then be configuring
 * their own oversight). Reading is open to any authenticated company user:
 * people should be able to see who the approvers are.
 */
@RestController
@RequestMapping("/api/onboarding/company/{companyId}/approver-pool")
class ApproverPoolController {

    private final ApproverPoolService approverPoolService;

    ApproverPoolController(ApproverPoolService approverPoolService) {
        this.approverPoolService = approverPoolService;
    }

    @GetMapping
    ApproverPoolResponse get(@PathVariable UUID companyId) {
        return approverPoolService.getPool(companyId);
    }

    @PostMapping("/members")
    @PreAuthorize("hasRole('ADMIN')")
    ApproverPoolResponse addMember(@PathVariable UUID companyId,
            @Valid @RequestBody AddApproverPoolMemberRequest request) {
        return approverPoolService.addMember(companyId, request.userId());
    }

    @DeleteMapping("/members/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    ApproverPoolResponse removeMember(@PathVariable UUID companyId, @PathVariable UUID userId) {
        return approverPoolService.removeMember(companyId, userId);
    }

    @PutMapping("/required-count")
    @PreAuthorize("hasRole('ADMIN')")
    ApproverPoolResponse setRequiredCount(@PathVariable UUID companyId,
            @Valid @RequestBody SetRequiredSignOffCountRequest request) {
        return approverPoolService.setRequiredCount(companyId, request.requiredSignOffCount());
    }
}
