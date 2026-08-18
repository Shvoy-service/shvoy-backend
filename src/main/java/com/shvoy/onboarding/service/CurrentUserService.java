package com.shvoy.onboarding.service;

import java.util.UUID;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.CurrentUserContext;
import com.shvoy.TenantContext;
import com.shvoy.UnauthenticatedException;
import com.shvoy.onboarding.domain.Role;
import com.shvoy.onboarding.domain.User;
import com.shvoy.onboarding.domain.UserStatus;
import com.shvoy.onboarding.dto.MeResponse;
import com.shvoy.onboarding.repository.CompanyRepository;
import com.shvoy.onboarding.repository.UserRepository;

/**
 * Resolves the current user's session context for {@code GET /api/me} — the
 * same {@code cognito_sub → profile} lookup that already runs on every
 * authenticated request (feeding {@code TenantContext} and role checks), just
 * exposed as a response. A read, so it lives in {@code onboarding} with the
 * users, not with any domain feature.
 *
 * <p>Status handling is the endpoint's own (not just the filter's), so it holds
 * under the local/test profiles where the JWT converter's ACTIVE gate doesn't
 * run: an {@code INACTIVE} profile is <strong>rejected</strong> (consistent with
 * the standing rule that a deactivated user can't act even with a valid token);
 * a {@code PENDING} profile is <strong>returned with its status</strong> so the
 * frontend can route to activation rather than dead-end on a 403; an unresolvable
 * identity is a stable 401, never a 500. (In dev/prod the JWT converter admits
 * only ACTIVE identities upstream, so those branches are the belt to its braces.)
 */
@Service
public class CurrentUserService {

    private static final UUID LOCAL_MOCK_USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ce");

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    /** Whether identity comes from debug headers (local/test) rather than a real token — gates the local mock. */
    private final boolean debugIdentity;

    CurrentUserService(UserRepository userRepository, CompanyRepository companyRepository, Environment environment) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.debugIdentity = environment.acceptsProfiles(Profiles.of("local", "test"));
    }

    @Transactional(readOnly = true)
    public MeResponse me() {
        UUID userId = CurrentUserContext.getOrNull();
        if (userId == null) {
            // No resolved identity. In local (auth disabled, no X-Debug-User-Id) return a coherent mock so a
            // frontend dev gets a working /me; anywhere a real token is required this can't happen (the auth
            // layer always sets the user), so treat it as unauthenticated.
            if (debugIdentity) {
                return localMock();
            }
            throw new UnauthenticatedException("No authenticated user");
        }

        // Tenant-scoped find: a token whose company doesn't match (cross-tenant) resolves to nothing here, so
        // tenant coherence is automatic — the profile is always the current tenant's.
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new UnauthenticatedException("No active SHVOY profile for this identity"));
        if (user.getStatus() == UserStatus.INACTIVE) {
            throw new UnauthenticatedException("No active SHVOY profile for this identity");
        }
        return toResponse(user.getId(), user.getEmail(), user.getRole(), user.getCompanyId(), user.getStatus());
    }

    /** The local-profile stand-in — a coherent ADMIN in the default tenant, so local /me never errors. */
    private MeResponse localMock() {
        UUID companyId = TenantContext.get();
        return toResponse(LOCAL_MOCK_USER_ID, "local-dev@shvoy.local", Role.ADMIN, companyId, UserStatus.ACTIVE);
    }

    private MeResponse toResponse(UUID userId, String email, Role role, UUID companyId, UserStatus status) {
        String companyName = companyRepository.findById(companyId)
            .map(com.shvoy.onboarding.domain.Company::getName)
            .orElse(null);
        return new MeResponse(userId, email, role, companyId, companyName, status);
    }
}
