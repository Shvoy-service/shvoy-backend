package com.shvoy.onboarding.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.onboarding.dto.InviteRequest;
import com.shvoy.onboarding.dto.InviteResponse;
import com.shvoy.onboarding.service.InvitationService;

@RestController
@RequestMapping("/api/onboarding/company/{companyId}/invite")
class InvitationController {

    private final InvitationService invitationService;

    InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<InviteResponse> invite(@PathVariable UUID companyId, @Valid @RequestBody InviteRequest request) {
        InviteResponse response = invitationService.invite(companyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
