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
     * The active {@code PURCHASING} + {@code ADMIN} users — the operational
     * audience that both resolves discrepancy cases (6.6) and decides container-fill
     * offers (8.2), so both notifications address it. Tenant-scoped like every
     * query; addresses only, so callers never need the {@code Role} vocabulary.
     */
    @Transactional(readOnly = true)
    public List<String> resolvePurchasingAndAdminEmails() {
        return userRepository.findAll().stream()
            .filter(user -> user.getStatus() == UserStatus.ACTIVE)
            .filter(user -> user.getRole() == Role.PURCHASING || user.getRole() == Role.ADMIN)
            .map(User::getEmail)
            .toList();
    }

    /** Discrepancy-resolver recipients (6.6) — the same active PURCHASING+ADMIN set, named for its first caller. */
    @Transactional(readOnly = true)
    public List<String> resolveDiscrepancyResolverEmails() {
        return resolvePurchasingAndAdminEmails();
    }

    /**
     * The active {@code APPROVER}-role users — who 5.5 notifies on the
     * single-approver (non-price-increase) path, where any approver can confirm,
     * as opposed to the price-increase path which notifies the eligible pool
     * ({@code ApproverPoolService#resolveEligibleApproverEmails}). Tenant-scoped
     * like every query; addresses only, the same minimal shape as the resolver
     * query above.
     */
    @Transactional(readOnly = true)
    public List<String> resolveApproverRoleEmails() {
        return userRepository.findAll().stream()
            .filter(user -> user.getStatus() == UserStatus.ACTIVE)
            .filter(user -> user.getRole() == Role.APPROVER)
            .map(User::getEmail)
            .toList();
    }
}
