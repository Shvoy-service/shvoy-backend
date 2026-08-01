package com.shvoy.onboarding.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.onboarding.dto.ActivateAccountRequest;
import com.shvoy.onboarding.dto.ActivateAccountResponse;
import com.shvoy.onboarding.service.RegistrationService;

/**
 * A separate, invite-flow-named entry point (paired with 2.3's
 * POST .../invite) onto the exact same token→password→ACTIVE operation
 * RegistrationController's /activate already uses for self-registered
 * admins — see RegistrationService.activate. Same deliberate exception to
 * Story 2.7's tenant enforcement as /activate: the caller has no account
 * yet, so this is permitted unauthenticated in SecurityConfig.
 */
@RestController
@RequestMapping("/api/onboarding/invite")
class InviteAcceptanceController {

    private final RegistrationService registrationService;

    InviteAcceptanceController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/accept")
    ResponseEntity<ActivateAccountResponse> accept(@Valid @RequestBody ActivateAccountRequest request) {
        return ResponseEntity.ok(registrationService.activate(request.token(), request.password()));
    }
}
