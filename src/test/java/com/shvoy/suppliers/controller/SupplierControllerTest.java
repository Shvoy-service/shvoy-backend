package com.shvoy.suppliers.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
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
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/**
 * No class-level @Transactional — see onboarding.repository.TenantIsolationTest
 * for why. TenantContextFilter sets TenantContext from the debug header
 * before the controller runs, so seeding via JDBC is all that's needed.
 * There's no {companyId} path segment here (unlike onboarding's
 * controllers) — see SupplierController's Javadoc — so the header is the
 * only thing establishing which company a request acts as.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupplierControllerTest {

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
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private UUID seedSupplier(UUID companyId, String name, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, ?, ?, ?)",
            id, name, status, Timestamp.from(Instant.now()), companyId);
        return id;
    }

    // --- list ---

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void listDefaultsToActiveOnlySortedByName() throws Exception {
        seedSupplier(companyA, "Zeta Supplies", "ACTIVE");
        seedSupplier(companyA, "Acme Corp", "ACTIVE");
        seedSupplier(companyA, "Inactive Co", "INACTIVE");
        seedSupplier(companyB, "Other Co", "ACTIVE");

        mockMvc.perform(get("/api/suppliers").header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].name").value("Acme Corp"))
            .andExpect(jsonPath("$[1].name").value("Zeta Supplies"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void listIncludeInactiveTrueReturnsBothStatuses() throws Exception {
        seedSupplier(companyA, "Active Co", "ACTIVE");
        seedSupplier(companyA, "Inactive Co", "INACTIVE");

        mockMvc.perform(get("/api/suppliers").param("includeInactive", "true")
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)));
    }

    // --- get ---

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void getReturnsOwnSupplier() throws Exception {
        UUID id = seedSupplier(companyA, "Acme Corp", "ACTIVE");

        mockMvc.perform(get("/api/suppliers/{id}", id).header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Acme Corp"))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void getForAnotherCompanysSupplierReturnsNotFound() throws Exception {
        UUID id = seedSupplier(companyB, "Other Co", "ACTIVE");

        mockMvc.perform(get("/api/suppliers/{id}", id).header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // --- create ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void createAssignsCallersCompanyFromContext() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/suppliers")
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme Corp\",\"country\":\"UK\",\"contactEmail\":\"ops@acme.example.com\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Acme Corp"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andReturn();

        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        UUID storedCompanyId = jdbcTemplate.queryForObject(
            "SELECT company_id FROM suppliers WHERE id = ?", UUID.class, UUID.fromString(id));
        assertThat(storedCompanyId).isEqualTo(companyA);
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void createWithBlankNameReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/suppliers")
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void createWithDuplicateNameCaseInsensitiveReturnsConflict() throws Exception {
        seedSupplier(companyA, "Acme Corp", "ACTIVE");

        mockMvc.perform(post("/api/suppliers")
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ACME CORP\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE_SUPPLIER"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void createDoesNotConflictWithADifferentCompanysSameName() throws Exception {
        seedSupplier(companyB, "Acme Corp", "ACTIVE");

        mockMvc.perform(post("/api/suppliers")
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme Corp\"}"))
            .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void createIsForbiddenForReadOnlyRole() throws Exception {
        mockMvc.perform(post("/api/suppliers")
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme Corp\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // --- update ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateReplacesFieldsAndRefreshesUpdatedAt() throws Exception {
        UUID id = seedSupplier(companyA, "Acme Corp", "ACTIVE");

        mockMvc.perform(put("/api/suppliers/{id}", id)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme Corporation\",\"country\":\"US\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Acme Corporation"))
            .andExpect(jsonPath("$.country").value("US"));

        var row = jdbcTemplate.queryForMap("SELECT name, country, updated_at FROM suppliers WHERE id = ?", id);
        assertThat(row.get("name")).isEqualTo("Acme Corporation");
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateForAnotherCompanysSupplierReturnsNotFoundAndLeavesItUnchanged() throws Exception {
        UUID id = seedSupplier(companyB, "Other Co", "ACTIVE");

        mockMvc.perform(put("/api/suppliers/{id}", id)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Hijacked\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        String name = jdbcTemplate.queryForObject("SELECT name FROM suppliers WHERE id = ?", String.class, id);
        assertThat(name).isEqualTo("Other Co");
    }

    @Test
    @WithMockUser(roles = "FINANCE")
    void updateIsForbiddenForFinanceRole() throws Exception {
        UUID id = seedSupplier(companyA, "Acme Corp", "ACTIVE");

        mockMvc.perform(put("/api/suppliers/{id}", id)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme Corporation\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateKeepingTheSameNameDoesNotConflictWithItself() throws Exception {
        UUID id = seedSupplier(companyA, "Acme Corp", "ACTIVE");

        mockMvc.perform(put("/api/suppliers/{id}", id)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme Corp\",\"country\":\"US\"}"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateToAnotherSuppliersNameReturnsConflict() throws Exception {
        seedSupplier(companyA, "Acme Corp", "ACTIVE");
        UUID targetId = seedSupplier(companyA, "Beta Supplies", "ACTIVE");

        mockMvc.perform(put("/api/suppliers/{id}", targetId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme Corp\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE_SUPPLIER"));
    }

    // --- deactivate ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void deactivateSoftDeletesRatherThanHardDeleting() throws Exception {
        UUID id = seedSupplier(companyA, "Acme Corp", "ACTIVE");

        mockMvc.perform(delete("/api/suppliers/{id}", id).header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("INACTIVE"));

        String status = jdbcTemplate.queryForObject("SELECT status FROM suppliers WHERE id = ?", String.class, id);
        assertThat(status).isEqualTo("INACTIVE");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deactivateForAnotherCompanysSupplierReturnsNotFoundAndLeavesItUnchanged() throws Exception {
        UUID id = seedSupplier(companyB, "Other Co", "ACTIVE");

        mockMvc.perform(delete("/api/suppliers/{id}", id).header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        String status = jdbcTemplate.queryForObject("SELECT status FROM suppliers WHERE id = ?", String.class, id);
        assertThat(status).isEqualTo("ACTIVE");
    }

    @Test
    @WithMockUser(roles = "APPROVER")
    void deactivateIsForbiddenForApproverRole() throws Exception {
        UUID id = seedSupplier(companyA, "Acme Corp", "ACTIVE");

        mockMvc.perform(delete("/api/suppliers/{id}", id).header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String status = jdbcTemplate.queryForObject("SELECT status FROM suppliers WHERE id = ?", String.class, id);
        assertThat(status).isEqualTo("ACTIVE");
    }
}
