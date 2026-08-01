package com.shvoy.onboarding.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.shvoy.LogCapture;
import com.shvoy.onboarding.service.InvitationService;

/**
 * No class-level @Transactional — see TenantIsolationTest for why. The
 * TenantContextFilter sets TenantContext from the request header before the
 * invite-creation call runs, so seeding via JDBC is all that's needed; the
 * accept call itself is deliberately unauthenticated/tenant-free (see
 * InviteAcceptanceController).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InviteAcceptanceControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PasswordEncoder passwordEncoder;

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

    @Test
    @WithMockUser(roles = "ADMIN")
    void validTokenActivatesInvitedUser() throws Exception {
        String email = uniqueEmail();
        String rawToken = invite(email, "FINANCE");

        mockMvc.perform(post("/api/onboarding/invite/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(acceptBody(rawToken, "correct horse battery")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.role").value("FINANCE"))
            .andExpect(jsonPath("$.status").value("ACTIVE"));

        var row = jdbcTemplate.queryForMap(
            "SELECT status, password_hash, company_id, verification_token FROM users WHERE email = ?", email);
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("company_id")).isEqualTo(companyA);
        assertThat(row.get("verification_token")).isNull();
        assertThat(passwordEncoder.matches("correct horse battery", (String) row.get("password_hash"))).isTrue();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void reusedTokenFailsOnSecondAttempt() throws Exception {
        String email = uniqueEmail();
        String rawToken = invite(email, "READ_ONLY");

        mockMvc.perform(post("/api/onboarding/invite/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(acceptBody(rawToken, "correct horse battery")))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/onboarding/invite/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(acceptBody(rawToken, "another password entirely")))
            .andExpect(status().isNotFound());

        String passwordHash = jdbcTemplate.queryForObject(
            "SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(passwordEncoder.matches("correct horse battery", passwordHash)).isTrue();
        assertThat(passwordEncoder.matches("another password entirely", passwordHash)).isFalse();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void expiredTokenIsRejected() throws Exception {
        String email = uniqueEmail();
        String rawToken = invite(email, "APPROVER");
        jdbcTemplate.update("UPDATE users SET verification_token_expires_at = ? WHERE email = ?",
            Timestamp.from(Instant.now().minusSeconds(3600)), email);

        mockMvc.perform(post("/api/onboarding/invite/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(acceptBody(rawToken, "correct horse battery")))
            .andExpect(status().isNotFound());

        String status = jdbcTemplate.queryForObject("SELECT status FROM users WHERE email = ?", String.class, email);
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void tamperedCompanyAndRoleInBodyAreIgnored() throws Exception {
        String email = uniqueEmail();
        String rawToken = invite(email, "PURCHASING");
        UUID otherCompany = UUID.randomUUID();

        String body = "{\"token\":\"" + rawToken + "\",\"password\":\"correct horse battery\","
            + "\"companyId\":\"" + otherCompany + "\",\"role\":\"ADMIN\"}";

        mockMvc.perform(post("/api/onboarding/invite/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("PURCHASING"));

        var row = jdbcTemplate.queryForMap("SELECT company_id, role FROM users WHERE email = ?", email);
        assertThat(row.get("company_id")).isEqualTo(companyA);
        assertThat(row.get("role")).isEqualTo("PURCHASING");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void concurrentDoubleSubmitActivatesAtMostOnce() throws Exception {
        String email = uniqueEmail();
        String rawToken = invite(email, "FINANCE");

        Callable<Integer> attemptA = () -> mockMvc.perform(post("/api/onboarding/invite/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(acceptBody(rawToken, "password from request A")))
            .andReturn().getResponse().getStatus();
        Callable<Integer> attemptB = () -> mockMvc.perform(post("/api/onboarding/invite/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(acceptBody(rawToken, "password from request B")))
            .andReturn().getResponse().getStatus();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> futures = executor.invokeAll(List.of(attemptA, attemptB));
            List<Integer> statuses = futures.stream().map(this::getUnchecked).collect(Collectors.toList());
            assertThat(statuses).containsExactlyInAnyOrder(200, 404);
        } finally {
            executor.shutdown();
        }

        String status = jdbcTemplate.queryForObject("SELECT status FROM users WHERE email = ?", String.class, email);
        String passwordHash = jdbcTemplate.queryForObject(
            "SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(status).isEqualTo("ACTIVE");
        boolean matchesA = passwordEncoder.matches("password from request A", passwordHash);
        boolean matchesB = passwordEncoder.matches("password from request B", passwordHash);
        assertThat(matchesA ^ matchesB).as("exactly one of the two racing passwords won").isTrue();
    }

    private Integer getUnchecked(Future<Integer> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String invite(String email, String role) throws Exception {
        try (LogCapture logs = new LogCapture(InvitationService.class)) {
            mockMvc.perform(post("/api/onboarding/company/{companyId}/invite", companyA)
                    .header(TENANT_HEADER, companyA.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated());
            return LogCapture.valueAfter(logs.firstMessageContaining("Invite link for " + email), "token=");
        }
    }

    private static String acceptBody(String token, String password) {
        return "{\"token\":\"" + token + "\",\"password\":\"" + password + "\"}";
    }

    private static String uniqueEmail() {
        return "member+" + UUID.randomUUID() + "@invite-test.example.com";
    }
}
