package com.shvoy.onboarding.service;

import java.util.List;

import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.onboarding.domain.Role;
import com.shvoy.onboarding.domain.User;
import com.shvoy.onboarding.domain.UserStatus;
import com.shvoy.onboarding.repository.UserRepository;

/**
 * A narrow cross-module surface (Story 6.6) for resolving notification
 * recipients by their governance role — {@code @NamedInterface} so {@code
 * payments} can address the discrepancy resolvers without reaching into the
 * onboarding module's user internals. Returns addresses only (no ids), the same
 * minimal shape as {@code ApproverPoolService#resolveEligibleApproverEmails}.
 */
@NamedInterface("user-directory")
@Service
public class UserDirectoryService {

    private final UserRepository userRepository;

    UserDirectoryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * The active users who resolve discrepancy cases — {@code PURCHASING}
     * ("resolves discrepancies") plus {@code ADMIN}. Tenant-scoped like every
     * query. The role keeps this a payments-facing method with no {@code Role}
     * parameter, so {@code payments} never needs the role vocabulary itself.
     */
    @Transactional(readOnly = true)
    public List<String> resolveDiscrepancyResolverEmails() {
        return userRepository.findAll().stream()
            .filter(user -> user.getStatus() == UserStatus.ACTIVE)
            .filter(user -> user.getRole() == Role.PURCHASING || user.getRole() == Role.ADMIN)
            .map(User::getEmail)
            .toList();
    }
}
