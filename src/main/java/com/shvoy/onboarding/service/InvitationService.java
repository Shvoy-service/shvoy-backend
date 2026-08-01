package com.shvoy.onboarding.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.NotFoundException;
import com.shvoy.TenantContext;
import com.shvoy.TenantGuard;
import com.shvoy.onboarding.domain.User;
import com.shvoy.onboarding.domain.UserStatus;
import com.shvoy.onboarding.dto.InviteRequest;
import com.shvoy.onboarding.dto.InviteResponse;
import com.shvoy.onboarding.repository.UserRepository;

/**
 * Re-inviting an email already PENDING in the caller's own company refreshes
 * its token and expiry in place rather than creating a second row — chosen
 * over inventing a resend/revoke API surface this story doesn't ask for.
 * The assigned role from the first invite is left untouched on refresh; the
 * story only calls for regenerating the token, not for reconciling a
 * possibly-different role on a second request.
 *
 * Email lookup here is raw JDBC, not a JpaRepository query method, for the
 * same reason as RegistrationService's uniqueness check: it must see PENDING
 * and ACTIVE users in *other* companies too (email is globally unique), and
 * Spring Data validates declared query methods against Hibernate at
 * repository-bean-creation time — before any tenant can possibly exist.
 */
@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);
    private static final Duration INVITE_TOKEN_TTL = Duration.ofDays(7);

    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    InvitationService(UserRepository userRepository, JdbcTemplate jdbcTemplate) {
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public InviteResponse invite(UUID companyId, InviteRequest request) {
        TenantGuard.assertOwnCompanyId(companyId);
        UUID callerCompanyId = TenantContext.get();

        String rawToken = SecureTokens.generate();
        Instant expiresAt = Instant.now().plus(INVITE_TOKEN_TTL);

        Map<String, Object> existing = findByEmail(request.email());
        User user;
        if (existing != null) {
            UUID existingCompanyId = (UUID) existing.get("company_id");
            UserStatus existingStatus = UserStatus.valueOf((String) existing.get("status"));
            boolean reinvitable = existingStatus == UserStatus.PENDING && existingCompanyId.equals(callerCompanyId);
            if (!reinvitable) {
                throw new ConflictException("Email already in use: " + request.email());
            }
            UUID existingId = (UUID) existing.get("id");
            user = userRepository.findById(existingId)
                .orElseThrow(() -> new NotFoundException("User not found"));
            user.issueVerificationToken(SecureTokens.hash(rawToken), expiresAt);
            user = userRepository.save(user);
        } else {
            User newUser = new User(request.email(), request.role());
            newUser.issueVerificationToken(SecureTokens.hash(rawToken), expiresAt);
            try {
                user = userRepository.save(newUser);
            } catch (DataIntegrityViolationException e) {
                // A concurrent invite/registration for the same email won
                // the race between our findByEmail check and this insert.
                throw new ConflictException("Email already in use: " + request.email());
            }
        }

        // Email delivery is a separate (notifications) feature — logged for
        // now so the flow is testable end to end without it.
        log.info("Invite link for {}: /api/onboarding/activate?token={}", request.email(), rawToken);

        return new InviteResponse(user.getEmail(), user.getRole(), user.getStatus());
    }

    private Map<String, Object> findByEmail(String email) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT id, company_id, status FROM users WHERE email = ?", email);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
