package com.shvoy.onboarding.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * No class-level @Transactional — see TenantIsolationTest for why. The
 * TenantContextFilter sets TenantContext from the request header before the
 * controller runs, so seeding via JDBC is all that's needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeamControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

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
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private UUID seedUser(UUID companyId, String email, String role, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id, cognito_sub) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, email, role, status, Timestamp.from(Instant.now()), companyId, UUID.randomUUID().toString());
        return id;
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void listReturnsAllOfTheCallersCompanyUsersWithNoSensitiveFields() throws Exception {
        seedUser(companyA, "admin@co-a.example.com", "ADMIN", "ACTIVE");
        seedUser(companyA, "pending@co-a.example.com", "FINANCE", "PENDING");
        seedUser(companyB, "other@co-b.example.com", "ADMIN", "ACTIVE");

        MvcResult result = mockMvc.perform(get("/api/onboarding/company/{companyId}/users", companyA)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andReturn();

        List<Map<String, Object>> body = objectMapper.readValue(
            result.getResponse().getContentAsString(), new TypeReference<>() {
            });
        assertThat(body).hasSize(2);
        assertThat(body).extracting(m -> m.get("email"), m -> m.get("role"), m -> m.get("status"))
            .containsExactlyInAnyOrder(
                tuple("admin@co-a.example.com", "ADMIN", "ACTIVE"),
                tuple("pending@co-a.example.com", "FINANCE", "PENDING"));
        assertThat(body).allSatisfy(member -> {
            assertThat(member).doesNotContainKey("cognitoSub");
            assertThat(member).doesNotContainKey("verificationToken");
            assertThat(member.keySet()).containsExactlyInAnyOrder("id", "email", "role", "status");
        });
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listForAnotherCompanyReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/onboarding/company/{companyId}/users", companyB)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanChangeRole() throws Exception {
        seedUser(companyA, "admin@co-a.example.com", "ADMIN", "ACTIVE");
        UUID targetId = seedUser(companyA, "member@co-a.example.com", "READ_ONLY", "ACTIVE");

        mockMvc.perform(put("/api/onboarding/company/{companyId}/users/{userId}/role", companyA, targetId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"FINANCE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("FINANCE"));

        String role = jdbcTemplate.queryForObject("SELECT role FROM users WHERE id = ?", String.class, targetId);
        assertThat(role).isEqualTo("FINANCE");
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void nonAdminCannotChangeRole() throws Exception {
        UUID targetId = seedUser(companyA, "member@co-a.example.com", "READ_ONLY", "ACTIVE");

        mockMvc.perform(put("/api/onboarding/company/{companyId}/users/{userId}/role", companyA, targetId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"FINANCE\"}"))
            .andExpect(status().isForbidden());

        String role = jdbcTemplate.queryForObject("SELECT role FROM users WHERE id = ?", String.class, targetId);
        assertThat(role).isEqualTo("READ_ONLY");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void changeRoleWithInvalidRoleReturns400() throws Exception {
        UUID targetId = seedUser(companyA, "member@co-a.example.com", "READ_ONLY", "ACTIVE");

        mockMvc.perform(put("/api/onboarding/company/{companyId}/users/{userId}/role", companyA, targetId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"SUPERADMIN\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void changeRoleForUserInAnotherCompanyReturnsNotFoundAndLeavesItUnchanged() throws Exception {
        UUID targetId = seedUser(companyB, "member@co-b.example.com", "READ_ONLY", "ACTIVE");

        mockMvc.perform(put("/api/onboarding/company/{companyId}/users/{userId}/role", companyA, targetId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        String role = jdbcTemplate.queryForObject("SELECT role FROM users WHERE id = ?", String.class, targetId);
        assertThat(role).isEqualTo("READ_ONLY");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void cannotDemoteTheLastActiveAdmin() throws Exception {
        UUID onlyAdmin = seedUser(companyA, "admin@co-a.example.com", "ADMIN", "ACTIVE");

        mockMvc.perform(put("/api/onboarding/company/{companyId}/users/{userId}/role", companyA, onlyAdmin)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"READ_ONLY\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LAST_ACTIVE_ADMIN"));

        String role = jdbcTemplate.queryForObject("SELECT role FROM users WHERE id = ?", String.class, onlyAdmin);
        assertThat(role).isEqualTo("ADMIN");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void canDemoteAnAdminWhenAnotherActiveAdminExists() throws Exception {
        seedUser(companyA, "admin1@co-a.example.com", "ADMIN", "ACTIVE");
        UUID secondAdmin = seedUser(companyA, "admin2@co-a.example.com", "ADMIN", "ACTIVE");

        mockMvc.perform(put("/api/onboarding/company/{companyId}/users/{userId}/role", companyA, secondAdmin)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"READ_ONLY\"}"))
            .andExpect(status().isOk());

        String role = jdbcTemplate.queryForObject("SELECT role FROM users WHERE id = ?", String.class, secondAdmin);
        assertThat(role).isEqualTo("READ_ONLY");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void pendingAdminDoesNotCountTowardsTheActiveAdminMinimum() throws Exception {
        UUID onlyActiveAdmin = seedUser(companyA, "admin@co-a.example.com", "ADMIN", "ACTIVE");
        seedUser(companyA, "invited-admin@co-a.example.com", "ADMIN", "PENDING");

        mockMvc.perform(put("/api/onboarding/company/{companyId}/users/{userId}/role", companyA, onlyActiveAdmin)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"READ_ONLY\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LAST_ACTIVE_ADMIN"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanDeactivateUser() throws Exception {
        seedUser(companyA, "admin@co-a.example.com", "ADMIN", "ACTIVE");
        UUID targetId = seedUser(companyA, "member@co-a.example.com", "READ_ONLY", "ACTIVE");

        mockMvc.perform(delete("/api/onboarding/company/{companyId}/users/{userId}", companyA, targetId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("INACTIVE"));

        var row = jdbcTemplate.queryForMap("SELECT status FROM users WHERE id = ?", targetId);
        assertThat(row.get("status")).isEqualTo("INACTIVE");
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void nonAdminCannotDeactivate() throws Exception {
        UUID targetId = seedUser(companyA, "member@co-a.example.com", "READ_ONLY", "ACTIVE");

        mockMvc.perform(delete("/api/onboarding/company/{companyId}/users/{userId}", companyA, targetId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String status = jdbcTemplate.queryForObject("SELECT status FROM users WHERE id = ?", String.class, targetId);
        assertThat(status).isEqualTo("ACTIVE");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void cannotDeactivateTheLastActiveAdmin() throws Exception {
        UUID onlyAdmin = seedUser(companyA, "admin@co-a.example.com", "ADMIN", "ACTIVE");

        mockMvc.perform(delete("/api/onboarding/company/{companyId}/users/{userId}", companyA, onlyAdmin)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LAST_ACTIVE_ADMIN"));

        String status = jdbcTemplate.queryForObject("SELECT status FROM users WHERE id = ?", String.class, onlyAdmin);
        assertThat(status).isEqualTo("ACTIVE");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void canDeactivateAnAdminWhenAnotherActiveAdminExists() throws Exception {
        seedUser(companyA, "admin1@co-a.example.com", "ADMIN", "ACTIVE");
        UUID secondAdmin = seedUser(companyA, "admin2@co-a.example.com", "ADMIN", "ACTIVE");

        mockMvc.perform(delete("/api/onboarding/company/{companyId}/users/{userId}", companyA, secondAdmin)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk());

        String status = jdbcTemplate.queryForObject("SELECT status FROM users WHERE id = ?", String.class, secondAdmin);
        assertThat(status).isEqualTo("INACTIVE");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deactivateForUserInAnotherCompanyReturnsNotFoundAndLeavesItUnchanged() throws Exception {
        UUID targetId = seedUser(companyB, "member@co-b.example.com", "READ_ONLY", "ACTIVE");

        mockMvc.perform(delete("/api/onboarding/company/{companyId}/users/{userId}", companyA, targetId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        String status = jdbcTemplate.queryForObject("SELECT status FROM users WHERE id = ?", String.class, targetId);
        assertThat(status).isEqualTo("ACTIVE");
    }
}
