package com.shvoy.onboarding.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.onboarding.dto.ActivateAccountRequest;
import com.shvoy.onboarding.dto.ActivateAccountResponse;
import com.shvoy.onboarding.dto.RegisterCompanyRequest;
import com.shvoy.onboarding.dto.RegisterCompanyResponse;
import com.shvoy.onboarding.service.RegistrationService;

/**
 * The one deliberate exception to Story 2.7's tenant enforcement: a caller
 * here has no company yet, so both endpoints must be reachable with no
 * authentication and no tenant context (see SecurityConfig, where they're
 * explicitly permitted ahead of everything else).
 */
@RestController
@RequestMapping("/api/onboarding")
class RegistrationController {

    private final RegistrationService registrationService;

    RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    ResponseEntity<RegisterCompanyResponse> register(@Valid @RequestBody RegisterCompanyRequest request) {
        RegisterCompanyResponse response = registrationService.register(request.email(), request.companyName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/activate")
    ResponseEntity<ActivateAccountResponse> activate(@Valid @RequestBody ActivateAccountRequest request) {
        return ResponseEntity.ok(registrationService.activate(request.token(), request.password()));
    }
}
