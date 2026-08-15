package com.shvoy.suppliers.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The reworked typed payment terms (supplier remodel): terms_type, nullable
 * deposit_pct, five-value anchor, current/target slots, and explicit target
 * activation. Type-consistency and the activation flow are the focus.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class PaymentTermsControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";
    private static final String USER_HEADER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID userAId;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "admin-a@example.com", now, companyA);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM supplier_audit_events WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update(
            "UPDATE suppliers SET current_term_id = NULL, target_term_id = NULL WHERE company_id IN (?, ?)",
            companyA, companyB);
        jdbcTemplate.update("DELETE FROM payment_terms WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void depositBalanceTermsRoundTrip() throws Exception {
        UUID supplierId = seedSupplier(companyA);
        mockMvc.perform(putTerms(supplierId, "",
                "{\"termsType\":\"DEPOSIT_BALANCE\",\"depositPct\":30.0,\"anchorDateType\":\"INVOICE\",\"daysFromAnchor\":30}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.current.termsType").value("DEPOSIT_BALANCE"))
            .andExpect(jsonPath("$.current.depositPct").value(30.0))
            .andExpect(jsonPath("$.current.anchorDateType").value("INVOICE"));
    }

    @Test
    void zeroDepositAndRollingRoundTripWithNullDeposit() throws Exception {
        UUID s1 = seedSupplier(companyA);
        mockMvc.perform(putTerms(s1, "",
                "{\"termsType\":\"ZERO_DEPOSIT\",\"anchorDateType\":\"BL\",\"daysFromAnchor\":-5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.current.termsType").value("ZERO_DEPOSIT"))
            .andExpect(jsonPath("$.current.depositPct").doesNotExist());

        UUID s2 = seedSupplier(companyA);
        mockMvc.perform(putTerms(s2, "",
                "{\"termsType\":\"ROLLING\",\"anchorDateType\":\"STATEMENT_DATE\",\"daysFromAnchor\":30}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.current.termsType").value("ROLLING"))
            .andExpect(jsonPath("$.current.anchorDateType").value("STATEMENT_DATE"));
    }

    @Test
    void depositBalanceWithoutPctIsRejected() throws Exception {
        UUID supplierId = seedSupplier(companyA);
        mockMvc.perform(putTerms(supplierId, "",
                "{\"termsType\":\"DEPOSIT_BALANCE\",\"anchorDateType\":\"INVOICE\",\"daysFromAnchor\":30}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_TERMS_COMBINATION"));
    }

    @Test
    void zeroDepositWithPctIsRejected() throws Exception {
        UUID supplierId = seedSupplier(companyA);
        mockMvc.perform(putTerms(supplierId, "",
                "{\"termsType\":\"ZERO_DEPOSIT\",\"depositPct\":0.0,\"anchorDateType\":\"BL\",\"daysFromAnchor\":30}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_TERMS_COMBINATION"));
    }

    @Test
    void statementAnchorOnNonRollingIsRejected() throws Exception {
        UUID supplierId = seedSupplier(companyA);
        mockMvc.perform(putTerms(supplierId, "",
                "{\"termsType\":\"ZERO_DEPOSIT\",\"anchorDateType\":\"STATEMENT_DATE\",\"daysFromAnchor\":30}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_TERMS_COMBINATION"));
    }

    @Test
    void activatingATargetPromotesItToCurrentAndAudits() throws Exception {
        UUID supplierId = seedSupplier(companyA);
        mockMvc.perform(putTerms(supplierId, "",
                "{\"termsType\":\"ZERO_DEPOSIT\",\"anchorDateType\":\"BL\",\"daysFromAnchor\":30}"))
            .andExpect(status().isOk());
        mockMvc.perform(putTerms(supplierId, "/target",
                "{\"termsType\":\"DEPOSIT_BALANCE\",\"depositPct\":25.0,\"anchorDateType\":\"INVOICE\",\"daysFromAnchor\":60}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.target.termsType").value("DEPOSIT_BALANCE"));

        mockMvc.perform(post("/api/suppliers/{s}/payment-terms/target/activate", supplierId)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.current.termsType").value("DEPOSIT_BALANCE"))
            .andExpect(jsonPath("$.target").doesNotExist());

        assertThat(auditCount(supplierId, "TERMS_TARGET_ACTIVATED")).isEqualTo(1);
    }

    @Test
    void activatingWithNoTargetIsRejected() throws Exception {
        UUID supplierId = seedSupplier(companyA);
        mockMvc.perform(post("/api/suppliers/{s}/payment-terms/target/activate", supplierId)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("NO_TARGET_TERM"));
    }

    @Test
    void cannotSetTermsForAnotherCompanysSupplier() throws Exception {
        UUID supplierB = seedSupplier(companyB);
        mockMvc.perform(putTerms(supplierB, "",
                "{\"termsType\":\"ZERO_DEPOSIT\",\"anchorDateType\":\"BL\",\"daysFromAnchor\":30}"))
            .andExpect(status().isNotFound());
    }

    // --- helpers ---

    private MockHttpServletRequestBuilder putTerms(UUID supplierId, String suffix, String body) {
        return put("/api/suppliers/{s}/payment-terms" + suffix, supplierId)
            .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId)
            .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private UUID seedSupplier(UUID companyId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, validation_status, created_at, company_id) "
                + "VALUES (?, ?, 'ACTIVE', 'PENDING', ?, ?)",
            id, "Supplier-" + id, Timestamp.from(Instant.now()), companyId);
        return id;
    }

    private int auditCount(UUID supplierId, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM supplier_audit_events WHERE supplier_id = ? AND event_type = ?",
            Integer.class, supplierId, eventType);
    }
}
