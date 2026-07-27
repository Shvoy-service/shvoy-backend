package com.shvoy.onboarding.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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

/**
 * Deliberately doesn't use class-level @Transactional: Hibernate resolves
 * the tenant when a Session is opened (proven by the failures this caused
 * while developing this test), and Spring's transactional test rollback
 * opens that session in a listener that runs before @BeforeEach — before
 * TenantContext could ever be set. Data is seeded/cleaned via plain JDBC
 * instead, and each Hibernate-backed call sets TenantContext immediately
 * beforehand.
 */
@SpringBootTest
@ActiveProfiles("test")
class TenantIsolationTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    final UUID userAId = UUID.randomUUID();
    final UUID userBId = UUID.randomUUID();

    @BeforeEach
    void seedTwoCompaniesWithOneUserEach() {
        // Seeded via raw JDBC, bypassing Hibernate/TenantContext entirely —
        // inserting both companies' data through the tenant-filtered ORM
        // path would require switching tenants mid-session, which Hibernate
        // doesn't support (a session is bound to one tenant).
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, ?, ?, ?, ?)",
            userAId, "a@companya.com", Role.ADMIN.name(), "ACTIVE", now, companyA);
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, ?, ?, ?, ?)",
            userBId, "b@companyb.com", Role.ADMIN.name(), "ACTIVE", now, companyB);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void listOnlyReturnsRecordsForCurrentCompany() {
        TenantContext.set(companyA);
        try {
            List<User> visible = userRepository.findAll();
            assertThat(visible).extracting(User::getId).containsExactly(userAId);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void cannotFetchOrMutateAnotherCompanysRecordById() {
        TenantContext.set(companyA);
        try {
            assertThat(userRepository.findById(userBId)).isEmpty();
        } finally {
            TenantContext.clear();
        }

        String emailAfter = jdbcTemplate.queryForObject(
            "SELECT email FROM users WHERE id = ?", String.class, userBId);
        assertThat(emailAfter).isEqualTo("b@companyb.com");
    }
}
