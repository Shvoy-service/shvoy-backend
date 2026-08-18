package com.shvoy.onboarding.controller;

import static org.hamcrest.Matchers.hasSize;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code GET /api/me} — the current user's session context. The frontend
 * generates its types from this response and gates its whole UI off {@code role},
 * so the exact-shape serialisation test is the cheap lock against a field rename
 * slipping through and silently breaking their session layer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "READ_ONLY")
class MeControllerTest {

    private static final String TENANT = "X-Debug-Company-Id";
    private static final String USER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Acme Imports Ltd", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Other Co", now);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void everyRoleGetsItsOwnContext() throws Exception {
        for (String role : new String[] {"ADMIN", "PURCHASING", "FINANCE", "APPROVER", "READ_ONLY"}) {
            UUID user = insertUser(companyA, role, "ACTIVE", role.toLowerCase() + "@acme.example");
            me(user, companyA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.toString()))
                .andExpect(jsonPath("$.role").value(role))
                .andExpect(jsonPath("$.companyId").value(companyA.toString()))
                .andExpect(jsonPath("$.companyName").value("Acme Imports Ltd"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        }
    }

    @Test
    void theResponseShapeMatchesTheContractExactly() throws Exception {
        UUID user = insertUser(companyA, "FINANCE", "ACTIVE", "fin@acme.example");

        me(user, companyA)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(user.toString()))
            .andExpect(jsonPath("$.email").value("fin@acme.example"))
            .andExpect(jsonPath("$.role").value("FINANCE"))
            .andExpect(jsonPath("$.companyId").value(companyA.toString()))
            .andExpect(jsonPath("$.companyName").value("Acme Imports Ltd"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            // Exactly these six fields — nothing added, nothing sensitive leaked.
            .andExpect(jsonPath("$.*", hasSize(6)))
            .andExpect(jsonPath("$.cognitoSub").doesNotExist())
            .andExpect(jsonPath("$.verificationToken").doesNotExist());
    }

    @Test
    void anInactiveProfileIsRejected() throws Exception {
        UUID user = insertUser(companyA, "FINANCE", "INACTIVE", "gone@acme.example");

        me(user, companyA)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void aPendingProfileIsReturnedWithItsStatus() throws Exception {
        UUID user = insertUser(companyA, "PURCHASING", "PENDING", "pending@acme.example");

        me(user, companyA)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING")) // returned, so the frontend routes to activation
            .andExpect(jsonPath("$.role").value("PURCHASING"));
    }

    @Test
    void anUnresolvableIdentityIsAStable401NotA500() throws Exception {
        me(UUID.randomUUID(), companyA) // valid-looking id, no matching profile
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void anotherTenantsUserResolvesToNoProfile() throws Exception {
        UUID user = insertUser(companyA, "ADMIN", "ACTIVE", "admin@acme.example");

        // Same user id, but the tenant is company B — the tenant-scoped lookup finds nothing, so no cross-tenant leak.
        me(user, companyB)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void theLocalStyleMockAnswersWhenNoUserIdentityIsSupplied() throws Exception {
        // No X-Debug-User-Id (auth-disabled local shape) — a coherent mock in the resolved tenant, never an error.
        mockMvc.perform(get("/api/me").header(TENANT, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("local-dev@shvoy.local"))
            .andExpect(jsonPath("$.role").value("ADMIN"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.companyId").value(companyA.toString()))
            .andExpect(jsonPath("$.companyName").value("Acme Imports Ltd"));
    }

    private ResultActions me(UUID userId, UUID companyId) throws Exception {
        return mockMvc.perform(get("/api/me")
            .header(TENANT, companyId.toString())
            .header(USER, userId.toString()));
    }

    private UUID insertUser(UUID company, String role, String statusValue, String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, ?, ?, ?, ?)",
            id, email, role, statusValue, Timestamp.from(Instant.now()), company);
        return id;
    }
}
