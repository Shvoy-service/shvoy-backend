package com.shvoy.onboarding.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.EmailMessage;
import com.shvoy.EmailSender;
import com.shvoy.ErrorCode;
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
 *
 * The invite email is sent via {@link EmailSender} (Story 4.7 extracted this
 * from what used to be a plain {@code log.info} call here, into a seam
 * {@code PurchaseOrderSendService} now shares) — see that interface's
 * Javadoc. {@code ConsoleEmailSender} is still the only implementation, so
 * the effective behaviour (a link logged to the console) is unchanged; only
 * which class does the logging changed — see {@code LogCapture} usages in
 * this flow's tests, which capture {@code ConsoleEmailSender}, not this
 * class, accordingly.
 */
@Service
public class InvitationService {

    private static final Duration INVITE_TOKEN_TTL = Duration.ofDays(7);

    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final EmailSender emailSender;

    InvitationService(UserRepository userRepository, JdbcTemplate jdbcTemplate, EmailSender emailSender) {
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.emailSender = emailSender;
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
                throw new ConflictException(ErrorCode.DUPLICATE_EMAIL, "Email already in use: " + request.email());
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
                throw new ConflictException(ErrorCode.DUPLICATE_EMAIL, "Email already in use: " + request.email());
            }
        }

        // Real email delivery is a separate (Notifications) feature — see
        // EmailSender's Javadoc. ConsoleEmailSender logs this for now so the
        // flow is testable end to end without it.
        emailSender.send(new EmailMessage(request.email(), "You've been invited to SHVOY",
            "Invite link for " + request.email() + ": /api/onboarding/activate?token=" + rawToken));

        return new InviteResponse(user.getEmail(), user.getRole(), user.getStatus());
    }

    private Map<String, Object> findByEmail(String email) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT id, company_id, status FROM users WHERE email = ?", email);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
