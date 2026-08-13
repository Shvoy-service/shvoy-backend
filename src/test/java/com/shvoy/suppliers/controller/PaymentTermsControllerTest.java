package com.shvoy.suppliers.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
 * Same conventions as SupplierControllerTest: no class-level @Transactional
 * (see onboarding.repository.TenantIsolationTest for why), JDBC seeding, the
 * debug tenant header rather than a {companyId} path segment.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentTermsControllerTest {

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
        jdbcTemplate.update("DELETE FROM payment_terms WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private UUID seedSupplier(UUID companyId, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            id, name, Timestamp.from(Instant.now()), companyId);
        return id;
    }

    // --- set (PUT) ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void setCreatesTermsAndDerivesBalance() throws Exception {
        UUID supplierId = seedSupplier(companyA, "Acme Corp");

        mockMvc.perform(put("/api/suppliers/{id}/payment-terms", supplierId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"depositPercentage\":30,\"anchorEvent\":\"BL\",\"daysOffset\":30}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.depositPercentage").value(30))
            .andExpect(jsonPath("$.balancePercentage").value(70))
            .andExpect(jsonPath("$.anchorEvent").value("BL"))
            .andExpect(jsonPath("$.daysOffset").value(30));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void setTwiceUpdatesTheExistingRowRatherThanInserting() throws Exception {
        UUID supplierId = seedSupplier(companyA, "Acme Corp");

        mockMvc.perform(put("/api/suppliers/{id}/payment-terms", supplierId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"depositPercentage\":30,\"anchorEvent\":\"BL\",\"daysOffset\":30}"))
            .andExpect(status().isOk());

        mockMvc.perform(put("/api/suppliers/{id}/payment-terms", supplierId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"depositPercentage\":50,\"anchorEvent\":\"INVOICE\",\"daysOffset\":45}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.depositPercentage").value(50))
            .andExpect(jsonPath("$.balancePercentage").value(50))
            .andExpect(jsonPath("$.anchorEvent").value("INVOICE"));

        Long rowCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_terms WHERE supplier_id = ?", Long.class, supplierId);
        assertThat(rowCount).isEqualTo(1L);

        Timestamp updatedAt = jdbcTemplate.queryForObject(
            "SELECT updated_at FROM payment_terms WHERE supplier_id = ?", Timestamp.class, supplierId);
        assertThat(updatedAt).isNotNull();
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void setForAnotherCompanysSupplierReturnsNotFound() throws Exception {
        UUID supplierId = seedSupplier(companyB, "Other Co");

        mockMvc.perform(put("/api/suppliers/{id}/payment-terms", supplierId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"depositPercentage\":30,\"anchorEvent\":\"BL\",\"daysOffset\":30}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        Long rowCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_terms WHERE supplier_id = ?", Long.class, supplierId);
        assertThat(rowCount).isZero();
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void setForNonexistentSupplierReturnsNotFound() throws Exception {
        mockMvc.perform(put("/api/suppliers/{id}/payment-terms", UUID.randomUUID())
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"depositPercentage\":30,\"anchorEvent\":\"BL\",\"daysOffset\":30}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void setIsForbiddenForReadOnlyRole() throws Exception {
        UUID supplierId = seedSupplier(companyA, "Acme Corp");

        mockMvc.perform(put("/api/suppliers/{id}/payment-terms", supplierId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"depositPercentage\":30,\"anchorEvent\":\"BL\",\"daysOffset\":30}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void setWithDepositAboveOneHundredReturnsValidationError() throws Exception {
        UUID supplierId = seedSupplier(companyA, "Acme Corp");

        mockMvc.perform(put("/api/suppliers/{id}/payment-terms", supplierId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"depositPercentage\":101,\"anchorEvent\":\"BL\",\"daysOffset\":30}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void setWithNegativeDaysOffsetReturnsValidationError() throws Exception {
        UUID supplierId = seedSupplier(companyA, "Acme Corp");

        mockMvc.perform(put("/api/suppliers/{id}/payment-terms", supplierId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"depositPercentage\":30,\"anchorEvent\":\"BL\",\"daysOffset\":-1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void setWithInvalidAnchorEventReturnsValidationError() throws Exception {
        UUID supplierId = seedSupplier(companyA, "Acme Corp");

        mockMvc.perform(put("/api/suppliers/{id}/payment-terms", supplierId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"depositPercentage\":30,\"anchorEvent\":\"SHIPPING\",\"daysOffset\":30}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // --- get ---

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void getReturnsTermsForAnyAuthenticatedRole() throws Exception {
        UUID supplierId = seedSupplier(companyA, "Acme Corp");
        jdbcTemplate.update(
            "INSERT INTO payment_terms (supplier_id, company_id, deposit_percentage, anchor_event, days_offset, "
                + "created_at) VALUES (?, ?, ?, ?, ?, ?)",
            supplierId, companyA, new BigDecimal("33.50"), "ARRIVAL", 15, Timestamp.from(Instant.now()));

        mockMvc.perform(get("/api/suppliers/{id}/payment-terms", supplierId).header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.depositPercentage").value(33.5))
            .andExpect(jsonPath("$.balancePercentage").value(66.5))
            .andExpect(jsonPath("$.anchorEvent").value("ARRIVAL"))
            .andExpect(jsonPath("$.daysOffset").value(15));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void getBeforeTermsAreSetReturnsNotFound() throws Exception {
        UUID supplierId = seedSupplier(companyA, "Acme Corp");

        mockMvc.perform(get("/api/suppliers/{id}/payment-terms", supplierId).header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void getForAnotherCompanysSupplierReturnsNotFound() throws Exception {
        UUID supplierId = seedSupplier(companyB, "Other Co");
        jdbcTemplate.update(
            "INSERT INTO payment_terms (supplier_id, company_id, deposit_percentage, anchor_event, days_offset, "
                + "created_at) VALUES (?, ?, ?, ?, ?, ?)",
            supplierId, companyB, new BigDecimal("30.00"), "BL", 30, Timestamp.from(Instant.now()));

        mockMvc.perform(get("/api/suppliers/{id}/payment-terms", supplierId).header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
