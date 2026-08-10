package com.shvoy.onboarding.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.shvoy.ConflictException;
import com.shvoy.ErrorCode;
import com.shvoy.IdentityProvider;
import com.shvoy.NotFoundException;
import com.shvoy.TenantContext;
import com.shvoy.onboarding.domain.Company;
import com.shvoy.onboarding.domain.Role;
import com.shvoy.onboarding.domain.User;
import com.shvoy.onboarding.domain.UserStatus;
import com.shvoy.onboarding.dto.ActivateAccountResponse;
import com.shvoy.onboarding.dto.RegisterCompanyResponse;
import com.shvoy.onboarding.repository.CompanyRepository;
import com.shvoy.onboarding.repository.UserRepository;

@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private static final Duration VERIFICATION_TOKEN_TTL = Duration.ofHours(24);

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final IdentityProvider identityProvider;
    private final TransactionTemplate transactionTemplate;

    RegistrationService(CompanyRepository companyRepository, UserRepository userRepository,
            JdbcTemplate jdbcTemplate, IdentityProvider identityProvider,
            PlatformTransactionManager transactionManager) {
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.identityProvider = identityProvider;
        // Programmatic transactions rather than @Transactional: a
        // @Transactional method's proxy opens the EntityManager (and so
        // needs a resolvable tenant) before the method body runs — too
        // early to call TenantContext.set() from inside it. Wrapping the
        // transaction explicitly, after the tenant is set, avoids that.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Creates a company and its first (ADMIN, PENDING) user in one
     * transaction. The company's id is generated here, in Java, rather than
     * left to the database — TenantContext must be set to it before either
     * repository is touched, since Hibernate needs a resolvable tenant to
     * open a session at all (see Company's Javadoc).
     */
    public RegisterCompanyResponse register(String email, String companyName) {
        UUID companyId = UUID.randomUUID();
        TenantContext.set(companyId);
        try {
            return transactionTemplate.execute(status -> {
                // Raw query, same reason as the token lookup below: email
                // must be unique across ALL companies, and a JpaRepository
                // query method here (even a native one) would need a
                // Hibernate session to even be *validated* at startup,
                // before any tenant context can possibly exist.
                Boolean emailTaken = jdbcTemplate.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM users WHERE email = ?)", Boolean.class, email);
                if (Boolean.TRUE.equals(emailTaken)) {
                    throw new ConflictException(ErrorCode.DUPLICATE_EMAIL, "Email already registered: " + email);
                }

                Company company = companyRepository.save(new Company(companyId, companyName));

                User admin = new User(email, Role.ADMIN);
                String token = SecureTokens.generate();
                admin.issueVerificationToken(SecureTokens.hash(token), Instant.now().plus(VERIFICATION_TOKEN_TTL));
                admin = userRepository.save(admin);

                // Email delivery is a separate (notifications) feature —
                // logged for now so the flow is testable end to end
                // without it.
                log.info("Verification link for {}: /api/onboarding/activate?token={}", email, token);

                return new RegisterCompanyResponse(company.getId(), admin.getId(), true);
            });
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Verifies a registration/invite token, provisions the corresponding
     * Cognito identity, and activates the account — shared by both
     * RegistrationController's self-registration /activate and
     * InviteAcceptanceController's /invite/accept, since both are exactly
     * this same operation. This is also the only place a Cognito user ever
     * gets created (not at register()/invite() time): it's the one point
     * where email, the caller's chosen password, and company_id/role are
     * all available together, and it's already the atomic chokepoint below.
     *
     * The activation itself is a single conditional UPDATE, not a read via
     * userRepository followed by a save: token consumption and activation
     * must be atomic, and a JPA read-then-write has a gap between them
     * where two concurrent submissions of the same token could both pass
     * their checks and both activate the account. A single UPDATE with the
     * PENDING/token/expiry conditions in its WHERE clause closes that gap —
     * only one of two racing UPDATEs can affect a still-PENDING row, so the
     * loser sees zero rows affected and is rejected exactly like an
     * unknown, expired, or already-consumed token. No new company_id or
     * role can enter through this path — both were already fixed when the
     * PENDING row was created (RegistrationService.register or
     * InvitationService.invite), and this method only ever writes the
     * Cognito sub and clears the token.
     *
     * The Cognito call happens before the UPDATE, so a Cognito failure
     * leaves no SHVOY-side trace at all. The reverse — Cognito succeeds but
     * the UPDATE affects zero rows, e.g. because this call lost the race
     * above, or the token had simply expired — leaves an orphaned Cognito
     * identity; that's compensated with a best-effort delete rather than
     * left dangling. If the compensating delete itself fails, a stale
     * Cognito identity for this email blocks a future attempt until it's
     * cleaned up manually — accepted as a residual gap rather than adding
     * retry/reconciliation machinery for what should be a rare failure.
     */
    public ActivateAccountResponse activate(String token, String password) {
        String tokenHash = SecureTokens.hash(token);

        UUID userId;
        String email;
        try {
            Map<String, Object> tokenRow = jdbcTemplate.queryForMap(
                "SELECT id, email FROM users WHERE verification_token = ?", tokenHash);
            userId = (UUID) tokenRow.get("id");
            email = (String) tokenRow.get("email");
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException(ErrorCode.INVALID_INVITE, "Invalid or expired invite");
        }

        String cognitoSub = identityProvider.createConfirmedUser(email, password);

        int updated = jdbcTemplate.update(
            "UPDATE users SET status = 'ACTIVE', cognito_sub = ?, verification_token = NULL, "
                + "verification_token_expires_at = NULL "
                + "WHERE id = ? AND verification_token = ? AND status = 'PENDING' "
                + "AND verification_token_expires_at > ?",
            cognitoSub, userId, tokenHash, Timestamp.from(Instant.now()));
        if (updated == 0) {
            try {
                identityProvider.deleteUser(email);
            } catch (RuntimeException e) {
                log.warn("Failed to delete orphaned Cognito user for {} after a lost activation race", email, e);
            }
            throw new NotFoundException(ErrorCode.INVALID_INVITE, "Invalid or expired invite");
        }

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT id, email, role, status FROM users WHERE id = ?", userId);
        return new ActivateAccountResponse((UUID) row.get("id"), (String) row.get("email"),
            Role.valueOf((String) row.get("role")), UserStatus.valueOf((String) row.get("status")));
    }
}
