package com.shvoy.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.shvoy.TenantContext;

/**
 * The approval-notification recipient query added in Story 9.5 — the
 * single-approver path emails all active {@code APPROVER}-role users. Same shape
 * as {@link ApproverPoolServiceTest}: seed via JDBC, set {@code TenantContext}
 * per call.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserDirectoryServiceTest {

    @Autowired
    UserDirectoryService userDirectoryService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();

    @BeforeEach
    void seedCompany() {
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)",
            companyA, "Co A", Timestamp.from(Instant.now()));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM users WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM companies WHERE id = ?", companyA);
    }

    private String seedUser(String role, String status) {
        UUID id = UUID.randomUUID();
        String email = "u-" + id + "@example.com";
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id, cognito_sub) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, email, role, status, Timestamp.from(Instant.now()), companyA, UUID.randomUUID().toString());
        return email;
    }

    @Test
    void resolveApproverRoleEmailsReturnsActiveApproversOnly() {
        String activeApprover = seedUser("APPROVER", "ACTIVE");
        seedUser("APPROVER", "INACTIVE");   // inactive — excluded
        seedUser("PURCHASING", "ACTIVE");   // wrong role — excluded
        seedUser("ADMIN", "ACTIVE");        // wrong role — excluded

        TenantContext.set(companyA);
        try {
            assertThat(userDirectoryService.resolveApproverRoleEmails()).containsExactly(activeApprover);
        } finally {
            TenantContext.clear();
        }
    }
}
