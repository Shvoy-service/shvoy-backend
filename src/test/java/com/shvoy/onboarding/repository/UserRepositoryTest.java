package com.shvoy.onboarding.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.shvoy.TenantContext;
import com.shvoy.onboarding.domain.Role;
import com.shvoy.onboarding.domain.User;

@SpringBootTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void seedCompany() {
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)",
            companyId, "Test Co", java.sql.Timestamp.from(java.time.Instant.now()));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM users WHERE company_id = ?", companyId);
        jdbcTemplate.update("DELETE FROM companies WHERE id = ?", companyId);
    }

    @Test
    void persistsAndReadsBackRoleAsEnumString() {
        // Hibernate resolves the tenant when a Session is opened, so
        // TenantContext must be set before any repository call, not just
        // before the assertions that use its result.
        TenantContext.set(companyId);
        try {
            User saved = userRepository.saveAndFlush(new User("admin@example.com", Role.ADMIN));

            User reloaded = userRepository.findById(saved.getId()).orElseThrow();
            assertThat(reloaded.getRole()).isEqualTo(Role.ADMIN);

            String rawValue = jdbcTemplate.queryForObject(
                "SELECT role FROM users WHERE id = ?", String.class, saved.getId());
            assertThat(rawValue).isEqualTo("ADMIN");
        } finally {
            TenantContext.clear();
        }
    }
}
