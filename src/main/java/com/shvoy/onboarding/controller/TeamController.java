package com.shvoy.onboarding.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.onboarding.dto.TeamMemberResponse;
import com.shvoy.onboarding.dto.UpdateUserRoleRequest;
import com.shvoy.onboarding.service.TeamManagementService;

@RestController
@RequestMapping("/api/onboarding/company/{companyId}/users")
class TeamController {

    private final TeamManagementService teamManagementService;

    TeamController(TeamManagementService teamManagementService) {
        this.teamManagementService = teamManagementService;
    }

    @GetMapping
    List<TeamMemberResponse> list(@PathVariable UUID companyId) {
        return teamManagementService.listUsers(companyId);
    }

    @PutMapping("/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    TeamMemberResponse changeRole(@PathVariable UUID companyId, @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserRoleRequest request) {
        return teamManagementService.changeRole(companyId, userId, request.role());
    }

    /**
     * 200 with the deactivated user rather than 204: gives the caller the
     * same immediate confirmation shape as the role-change endpoint,
     * without a follow-up GET.
     */
    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    TeamMemberResponse deactivate(@PathVariable UUID companyId, @PathVariable UUID userId) {
        return teamManagementService.deactivate(companyId, userId);
    }
}
