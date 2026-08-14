package com.shvoy.onboarding.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 5.6 — approver pool configuration. No class-level @Transactional;
 * seed via JDBC, tenant from the debug header (same convention as
 * TeamControllerTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApproverPoolControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();

    @BeforeEach
    void seedCompanies() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM approver_pool_members WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM approver_pool_settings WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private UUID seedUser(UUID companyId, String role, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id, cognito_sub) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, "u-" + id + "@example.com", role, status, Timestamp.from(Instant.now()), companyId,
            UUID.randomUUID().toString());
        return id;
    }

    private void seedPoolMember(UUID companyId, UUID userId) {
        jdbcTemplate.update(
            "INSERT INTO approver_pool_members (id, user_id, created_at, company_id) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(), userId, Timestamp.from(Instant.now()), companyId);
    }

    private void seedRequiredCount(UUID companyId, int count) {
        jdbcTemplate.update(
            "INSERT INTO approver_pool_settings (id, required_sign_off_count, created_at, company_id) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(), count, Timestamp.from(Instant.now()), companyId);
    }

    private void addMember(UUID userId) throws Exception {
        mockMvc.perform(post("/api/onboarding/company/{companyId}/approver-pool/members", companyA)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\"}"))
            .andExpect(status().isOk());
    }

    private void setRequiredCount(int count) throws Exception {
        mockMvc.perform(put("/api/onboarding/company/{companyId}/approver-pool/required-count", companyA)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requiredSignOffCount\":" + count + "}"))
            .andExpect(status().isOk());
    }

    // --- happy path: a valid 2-of-3 pool ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void configureAValidTwoOfThreePool() throws Exception {
        UUID a = seedUser(companyA, "APPROVER", "ACTIVE");
        UUID b = seedUser(companyA, "APPROVER", "ACTIVE");
        UUID c = seedUser(companyA, "APPROVER", "ACTIVE");

        addMember(a);
        addMember(b);
        addMember(c);
        setRequiredCount(2);

        mockMvc.perform(get("/api/onboarding/company/{companyId}/approver-pool", companyA)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requiredSignOffCount").value(2))
            .andExpect(jsonPath("$.usingDefaultRequiredCount").value(false))
            .andExpect(jsonPath("$.eligibleMemberCount").value(3))
            .andExpect(jsonPath("$.members.length()").value(3))
            .andExpect(jsonPath("$.members[0].eligible").value(true));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void getReturnsTheDefaultRequiredCountWhenUnset() throws Exception {
        mockMvc.perform(get("/api/onboarding/company/{companyId}/approver-pool", companyA)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requiredSignOffCount").value(2))
            .andExpect(jsonPath("$.usingDefaultRequiredCount").value(true))
            .andExpect(jsonPath("$.eligibleMemberCount").value(0))
            .andExpect(jsonPath("$.members").isEmpty());
    }

    // --- the load-bearing validation: count must not exceed active pool size ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void requiredCountExceedingPoolSizeIsRejected() throws Exception {
        UUID a = seedUser(companyA, "APPROVER", "ACTIVE");
        UUID b = seedUser(companyA, "APPROVER", "ACTIVE");
        addMember(a);
        addMember(b);

        // Pool of 2, required count of 3 → the gate could never be met.
        mockMvc.perform(put("/api/onboarding/company/{companyId}/approver-pool/required-count", companyA)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requiredSignOffCount\":3}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("APPROVER_COUNT_EXCEEDS_POOL"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void requiredCountBelowOneIsRejectedAsValidationError() throws Exception {
        mockMvc.perform(put("/api/onboarding/company/{companyId}/approver-pool/required-count", companyA)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requiredSignOffCount\":0}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // --- member eligibility ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void addingANonApproverRoleUserIsRejected() throws Exception {
        UUID purchasing = seedUser(companyA, "PURCHASING", "ACTIVE");

        mockMvc.perform(post("/api/onboarding/company/{companyId}/approver-pool/members", companyA)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + purchasing + "\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INELIGIBLE_APPROVER"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void addingAnInactiveApproverIsRejected() throws Exception {
        UUID inactive = seedUser(companyA, "APPROVER", "INACTIVE");

        mockMvc.perform(post("/api/onboarding/company/{companyId}/approver-pool/members", companyA)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + inactive + "\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INELIGIBLE_APPROVER"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void addingACrossTenantUserReturnsNotFound() throws Exception {
        UUID otherCompanyApprover = seedUser(companyB, "APPROVER", "ACTIVE");

        mockMvc.perform(post("/api/onboarding/company/{companyId}/approver-pool/members", companyA)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + otherCompanyApprover + "\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // --- removal guard ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void removingAMemberThatWouldBreakTheRequiredCountIsRejected() throws Exception {
        UUID a = seedUser(companyA, "APPROVER", "ACTIVE");
        UUID b = seedUser(companyA, "APPROVER", "ACTIVE");
        seedPoolMember(companyA, a);
        seedPoolMember(companyA, b);
        seedRequiredCount(companyA, 2);

        // Removing either leaves 1 active member < required 2.
        mockMvc.perform(delete("/api/onboarding/company/{companyId}/approver-pool/members/{userId}", companyA, a)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("APPROVER_COUNT_EXCEEDS_POOL"));
    }

    // --- deactivation is handled at use time, without mutating the pool ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void aDeactivatedMemberIsExcludedFromTheEligibleSetButStaysInThePool() throws Exception {
        UUID a = seedUser(companyA, "APPROVER", "ACTIVE");
        UUID b = seedUser(companyA, "APPROVER", "ACTIVE");
        UUID c = seedUser(companyA, "APPROVER", "INACTIVE"); // deactivated after having joined
        seedPoolMember(companyA, a);
        seedPoolMember(companyA, b);
        seedPoolMember(companyA, c);

        mockMvc.perform(get("/api/onboarding/company/{companyId}/approver-pool", companyA)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            // Still listed (no silent mutation) — 3 members — but only 2 are eligible.
            .andExpect(jsonPath("$.members.length()").value(3))
            .andExpect(jsonPath("$.eligibleMemberCount").value(2))
            .andExpect(jsonPath("$.members[?(@.userId == '" + c + "')].eligible").value(false));
    }

    // --- authorisation & tenancy ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void nonAdminCannotConfigureThePool() throws Exception {
        UUID approver = seedUser(companyA, "APPROVER", "ACTIVE");

        mockMvc.perform(post("/api/onboarding/company/{companyId}/approver-pool/members", companyA)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + approver + "\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void configuringAnotherCompanysPoolReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/onboarding/company/{companyId}/approver-pool", companyB)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
