package com.shvoy.onboarding.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.onboarding.domain.Role;
import com.shvoy.onboarding.domain.User;
import com.shvoy.onboarding.domain.UserStatus;
import com.shvoy.onboarding.dto.TeamMemberResponse;
import com.shvoy.onboarding.repository.UserRepository;

@Service
public class TeamManagementService {

    private final UserRepository userRepository;

    TeamManagementService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<TeamMemberResponse> listUsers(UUID companyId) {
        TenantGuard.assertOwnCompanyId(companyId);
        return userRepository.findAll().stream()
            .map(TeamManagementService::toResponse)
            .toList();
    }

    @Transactional
    public TeamMemberResponse changeRole(UUID companyId, UUID userId, Role newRole) {
        TenantGuard.assertOwnCompanyId(companyId);
        User user = findOwnUser(userId);
        guardLastActiveAdmin(user, newRole, user.getStatus());
        user.changeRole(newRole);
        return toResponse(userRepository.save(user));
    }

    @Transactional
    public TeamMemberResponse deactivate(UUID companyId, UUID userId) {
        TenantGuard.assertOwnCompanyId(companyId);
        User user = findOwnUser(userId);
        guardLastActiveAdmin(user, user.getRole(), UserStatus.INACTIVE);
        user.deactivate();
        return toResponse(userRepository.save(user));
    }

    /**
     * Blocks any change that would leave zero ACTIVE admins in the company —
     * covers both demoting an admin away from the ADMIN role and
     * deactivating one, and applies identically whether the target is the
     * caller or someone else: there's no per-user session identity in this
     * app yet (no login endpoint exists), so "self" isn't even expressible
     * here — and the actual risk, a company locked out of all admin
     * functions, is the same regardless of whose account it is. Only ACTIVE
     * admins count toward the minimum: a PENDING admin who hasn't accepted
     * their invite can't perform any admin action, so treating them as a
     * safety net would be misleading.
     *
     * Known gap: this reads then writes across two separate statements, so
     * two concurrent removals of two *different* admins (A removes B, B
     * removes A, at once) could both read the other as still active and
     * both succeed, leaving zero admins — the same class of race
     * RegistrationService.activate closes with a single conditional UPDATE.
     * Not closed here: doing so needs a lock across every admin row in the
     * company, not a single row's WHERE clause, and there's no real
     * concurrent-admin-session path to trigger it yet (no auth wiring — see
     * SecurityConfig). Worth revisiting once real sessions exist.
     */
    private void guardLastActiveAdmin(User target, Role resultingRole, UserStatus resultingStatus) {
        boolean losingActiveAdmin = target.getRole() == Role.ADMIN && target.getStatus() == UserStatus.ACTIVE
            && (resultingRole != Role.ADMIN || resultingStatus != UserStatus.ACTIVE);
        if (!losingActiveAdmin) {
            return;
        }
        boolean anotherActiveAdminExists = userRepository.findAll().stream()
            .anyMatch(u -> !u.getId().equals(target.getId())
                && u.getRole() == Role.ADMIN && u.getStatus() == UserStatus.ACTIVE);
        if (!anotherActiveAdminExists) {
            throw new ConflictException("Company must have at least one active admin");
        }
    }

    private User findOwnUser(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        TenantGuard.assertOwned(user);
        return user;
    }

    private static TeamMemberResponse toResponse(User user) {
        return new TeamMemberResponse(user.getId(), user.getEmail(), user.getRole(), user.getStatus());
    }
}
