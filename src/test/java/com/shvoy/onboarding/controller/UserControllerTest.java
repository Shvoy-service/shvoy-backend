package com.shvoy.onboarding.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.shvoy.onboarding.domain.Role;

/**
 * No class-level @Transactional — see TenantIsolationTest for why. The
 * TenantContextFilter sets TenantContext from the request header before the
 * controller (and its own repository call) runs, so no extra setup is
 * needed here beyond seeding via JDBC.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    final UUID userAId = UUID.randomUUID();
    final UUID userBId = UUID.randomUUID();

    @BeforeEach
    void seedTwoCompaniesWithOneUserEach() {
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
    void fetchingOwnCompanysUserSucceeds() throws Exception {
        mockMvc.perform(get("/api/onboarding/users/{id}", userAId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("a@companya.com"));
    }

    @Test
    void fetchingAnotherCompanysUserReturnsNotFoundNotForbidden() throws Exception {
        mockMvc.perform(get("/api/onboarding/users/{id}", userBId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
