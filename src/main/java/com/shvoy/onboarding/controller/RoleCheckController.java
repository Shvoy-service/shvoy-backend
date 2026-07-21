package com.shvoy.onboarding.controller;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reference endpoint for the method-security mechanism established in
 * Story 2.5. Future stories protect real endpoints the same way, with
 * {@code @PreAuthorize} expressions referencing {@link com.shvoy.onboarding.domain.Role}
 * names.
 */
@RestController
@RequestMapping("/api/onboarding")
class RoleCheckController {

    @GetMapping("/role-check/admin-only")
    @PreAuthorize("hasRole('ADMIN')")
    Map<String, String> adminOnly() {
        return Map.of("status", "ok");
    }
}
