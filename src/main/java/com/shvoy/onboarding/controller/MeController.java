package com.shvoy.onboarding.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shvoy.onboarding.dto.MeResponse;
import com.shvoy.onboarding.service.CurrentUserService;

/**
 * {@code GET /api/me} — the current user's session context. Cross-cutting
 * session bootstrap (every client hits it, every role), so it sits at the API
 * root rather than under the module's {@code /api/onboarding} workflow prefix.
 *
 * <p>Authenticated, no role restriction — every role, {@code READ_ONLY}
 * included, needs to know who it is. No parameters and no user id in the path:
 * the identity is the validated token's {@code sub}, full stop, so there is
 * nothing to tamper with.
 */
@RestController
class MeController {

    private final CurrentUserService currentUserService;

    MeController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/me")
    MeResponse me() {
        return currentUserService.me();
    }
}
