package com.shvoy.onboarding.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * No class-level @Transactional — see TenantIsolationTest for why. The
 * TenantContextFilter sets TenantContext from the request header before the
 * controller runs, so seeding via JDBC is all that's needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CompanyProfileControllerTest {

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
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void getReturnsOwnCompanysProfile() throws Exception {
        mockMvc.perform(get("/api/onboarding/company/{companyId}/profile", companyA)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(companyA.toString()))
            .andExpect(jsonPath("$.name").value("Co A"))
            .andExpect(jsonPath("$.registeredAddress").value(nullValue()));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void getIsAllowedForAnyAuthenticatedRole() throws Exception {
        mockMvc.perform(get("/api/onboarding/company/{companyId}/profile", companyA)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Co A"));
    }

    @Test
    void getForAnotherCompanyReturnsNotFoundNotForbidden() throws Exception {
        mockMvc.perform(get("/api/onboarding/company/{companyId}/profile", companyB)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void putUpdatesProfileAndRefreshesUpdatedAt() throws Exception {
        mockMvc.perform(put("/api/onboarding/company/{companyId}/profile", companyA)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"registeredAddress":"1 Example St","country":"UK",
                     "contactEmail":"ops@co-a.example.com","contactPhone":"+44 20 1234 5678",
                     "registrationNumber":"12345678"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.registeredAddress").value("1 Example St"))
            .andExpect(jsonPath("$.country").value("UK"))
            .andExpect(jsonPath("$.contactEmail").value("ops@co-a.example.com"))
            .andExpect(jsonPath("$.updatedAt").exists());

        String updatedAt = jdbcTemplate.queryForObject(
            "SELECT updated_at FROM companies WHERE id = ?", String.class, companyA);
        assertThat(updatedAt).isNotNull();
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void putByNonAdminIsForbidden() throws Exception {
        mockMvc.perform(put("/api/onboarding/company/{companyId}/profile", companyA)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"country\":\"UK\"}"))
            .andExpect(status().isForbidden());

        String country = jdbcTemplate.queryForObject(
            "SELECT country FROM companies WHERE id = ?", String.class, companyA);
        assertThat(country).isNull();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void putForAnotherCompanyReturnsNotFoundAndLeavesItUntouched() throws Exception {
        mockMvc.perform(put("/api/onboarding/company/{companyId}/profile", companyB)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"country\":\"UK\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        String country = jdbcTemplate.queryForObject(
            "SELECT country FROM companies WHERE id = ?", String.class, companyB);
        assertThat(country).isNull();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void malformedContactEmailReturns400() throws Exception {
        mockMvc.perform(put("/api/onboarding/company/{companyId}/profile", companyA)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contactEmail\":\"not-an-email\"}"))
            .andExpect(status().isBadRequest());
    }
}
