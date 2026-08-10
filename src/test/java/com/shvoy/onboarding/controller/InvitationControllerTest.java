package com.shvoy.onboarding.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.shvoy.LogCapture;
import com.shvoy.onboarding.service.InvitationService;

/**
 * No class-level @Transactional — see TenantIsolationTest for why. The
 * TenantContextFilter sets TenantContext from the request header before the
 * controller runs, so seeding via JDBC is all that's needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvitationControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();

    @BeforeEach
    void seedTwoCompanies() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void nonAdminCannotInvite() throws Exception {
        mockMvc.perform(post("/api/onboarding/company/{companyId}/invite", companyA)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(inviteBody(uniqueEmail(), "FINANCE")))
            .andExpect(status().isForbidden());

        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE company_id = ?", Integer.class, companyA);
        assertThat(count).isZero();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminInvitesCreatesPendingUserWithValidUnexpiredScopedToken() throws Exception {
        String email = uniqueEmail();
        String rawToken;
        try (LogCapture logs = new LogCapture(InvitationService.class)) {
            mockMvc.perform(post("/api/onboarding/company/{companyId}/invite", companyA)
                    .header(TENANT_HEADER, companyA.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(inviteBody(email, "FINANCE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("FINANCE"))
                .andExpect(jsonPath("$.status").value("PENDING"));
            rawToken = LogCapture.valueAfter(logs.firstMessageContaining("Invite link for " + email), "token=");
        }

        var row = jdbcTemplate.queryForMap(
            "SELECT company_id, status, verification_token, verification_token_expires_at "
                + "FROM users WHERE email = ?", email);
        assertThat(row.get("company_id")).isEqualTo(companyA);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("verification_token")).isNotEqualTo(rawToken);
        assertThat(((String) row.get("verification_token"))).hasSize(64); // SHA-256 hex digest

        Instant expiresAt = ((OffsetDateTime) row.get("verification_token_expires_at")).toInstant();
        assertThat(expiresAt).isAfter(Instant.now().plusSeconds(6L * 24 * 3600));
        assertThat(expiresAt).isBefore(Instant.now().plusSeconds(8L * 24 * 3600));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void malformedEmailReturns400() throws Exception {
        mockMvc.perform(post("/api/onboarding/company/{companyId}/invite", companyA)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(inviteBody("not-an-email", "FINANCE")))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void invalidRoleReturns400() throws Exception {
        mockMvc.perform(post("/api/onboarding/company/{companyId}/invite", companyA)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(inviteBody(uniqueEmail(), "SUPERADMIN")))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void duplicateActiveEmailReturns409() throws Exception {
        String email = uniqueEmail();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), email, "FINANCE", "ACTIVE", now, companyA);

        mockMvc.perform(post("/api/onboarding/company/{companyId}/invite", companyA)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(inviteBody(email, "FINANCE")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void pendingEmailInAnotherCompanyReturns409AndLeavesItUntouched() throws Exception {
        String email = uniqueEmail();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id, verification_token, "
                + "verification_token_expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), email, "READ_ONLY", "PENDING", now, companyB, "original-hash",
            Timestamp.from(Instant.now().plusSeconds(3600)));

        mockMvc.perform(post("/api/onboarding/company/{companyId}/invite", companyA)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(inviteBody(email, "FINANCE")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));

        String storedToken = jdbcTemplate.queryForObject(
            "SELECT verification_token FROM users WHERE email = ?", String.class, email);
        assertThat(storedToken).isEqualTo("original-hash");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void reinvitingPendingEmailInOwnCompanyRefreshesTokenWithoutDuplicating() throws Exception {
        String email = uniqueEmail();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id, verification_token, "
                + "verification_token_expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), email, "READ_ONLY", "PENDING", now, companyA, "stale-hash",
            Timestamp.from(Instant.now().plusSeconds(60)));

        mockMvc.perform(post("/api/onboarding/company/{companyId}/invite", companyA)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(inviteBody(email, "READ_ONLY")))
            .andExpect(status().isCreated());

        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(count).isEqualTo(1);

        var row = jdbcTemplate.queryForMap(
            "SELECT verification_token, verification_token_expires_at FROM users WHERE email = ?", email);
        assertThat(row.get("verification_token")).isNotEqualTo("stale-hash");
        Instant expiresAt = ((OffsetDateTime) row.get("verification_token_expires_at")).toInstant();
        assertThat(expiresAt).isAfter(Instant.now().plusSeconds(6L * 24 * 3600));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void invitingIntoAnotherCompanyReturnsNotFoundAndCreatesNothing() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post("/api/onboarding/company/{companyId}/invite", companyB)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(inviteBody(email, "FINANCE")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(count).isZero();
    }

    private static String inviteBody(String email, String role) {
        return "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}";
    }

    private static String uniqueEmail() {
        return "member+" + UUID.randomUUID() + "@invite-test.example.com";
    }
}
