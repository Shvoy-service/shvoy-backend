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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.shvoy.ConflictException;
import com.shvoy.NotFoundException;
import com.shvoy.TenantContext;
import com.shvoy.onboarding.domain.Company;
import com.shvoy.onboarding.domain.Role;
import com.shvoy.onboarding.domain.User;
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
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;

    RegistrationService(CompanyRepository companyRepository, UserRepository userRepository,
            JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager) {
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
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
                    throw new ConflictException("Email already registered: " + email);
                }

                Company company = companyRepository.save(new Company(companyId, companyName));

                User admin = new User(email, Role.ADMIN);
                String token = UUID.randomUUID().toString();
                admin.issueVerificationToken(token, Instant.now().plus(VERIFICATION_TOKEN_TTL));
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
     * Verifies a registration/invite token, sets the password, and
     * activates the account. The token lookup is a raw query for the same
     * reason as above: which company this user belongs to isn't known
     * until the token itself tells us.
     */
    public void activate(String token, String password) {
        Map<String, Object> row;
        try {
            row = jdbcTemplate.queryForMap(
                "SELECT id, company_id FROM users "
                    + "WHERE verification_token = ? AND status = 'PENDING' AND verification_token_expires_at > ?",
                token, Timestamp.from(Instant.now()));
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("Invalid or expired activation token");
        }

        UUID userId = (UUID) row.get("id");
        UUID companyId = (UUID) row.get("company_id");

        TenantContext.set(companyId);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                User user = userRepository.findById(userId)
                    .orElseThrow(() -> new NotFoundException("Invalid or expired activation token"));
                user.activate(passwordEncoder.encode(password));
                userRepository.save(user);
            });
        } finally {
            TenantContext.clear();
        }
    }
}
