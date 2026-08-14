package com.shvoy.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
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
 * The cross-module read surface 5.5 consumes ({@code resolveEligibleApprovers}
 * / {@code requiredSignOffCount}), tested directly — it's what the 2-of-N gate
 * routes and counts against. No class-level @Transactional; seed via JDBC and
 * set {@code TenantContext} per call, same as the repository isolation tests.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApproverPoolServiceTest {

    @Autowired
    ApproverPoolService approverPoolService;

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
        jdbcTemplate.update("DELETE FROM approver_pool_members WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM approver_pool_settings WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM users WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM companies WHERE id = ?", companyA);
    }

    private UUID seedUser(String role, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id, cognito_sub) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, "u-" + id + "@example.com", role, status, Timestamp.from(Instant.now()), companyA,
            UUID.randomUUID().toString());
        return id;
    }

    private void seedPoolMember(UUID userId) {
        jdbcTemplate.update(
            "INSERT INTO approver_pool_members (id, user_id, created_at, company_id) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(), userId, Timestamp.from(Instant.now()), companyA);
    }

    @Test
    void resolveEligibleApproversExcludesDeactivatedAndNonApproverMembers() {
        UUID active1 = seedUser("APPROVER", "ACTIVE");
        UUID active2 = seedUser("APPROVER", "ACTIVE");
        UUID deactivated = seedUser("APPROVER", "INACTIVE");
        seedPoolMember(active1);
        seedPoolMember(active2);
        seedPoolMember(deactivated);

        TenantContext.set(companyA);
        try {
            Set<UUID> eligible = approverPoolService.resolveEligibleApprovers();
            assertThat(eligible).containsExactlyInAnyOrder(active1, active2);
            assertThat(eligible).doesNotContain(deactivated);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void requiredSignOffCountFallsBackToTheDefaultWhenUnset() {
        TenantContext.set(companyA);
        try {
            assertThat(approverPoolService.requiredSignOffCount()).isEqualTo(2);
        } finally {
            TenantContext.clear();
        }
    }
}
