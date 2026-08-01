package com.shvoy.onboarding.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.onboarding.domain.User;
import com.shvoy.onboarding.dto.UserResponse;
import com.shvoy.onboarding.repository.UserRepository;

/**
 * Reference endpoint for the tenant-isolation mechanism established in
 * Story 2.7. Real user-management endpoints (list team, invite, etc.) are
 * built on this same pattern in later stories.
 */
@RestController
@RequestMapping("/api/onboarding/users")
class UserController {

    private final UserRepository userRepository;

    UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/{id}")
    UserResponse getById(@PathVariable UUID id) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("User not found"));
        TenantGuard.assertOwned(user);
        return new UserResponse(user.getId(), user.getEmail(), user.getRole());
    }
}
