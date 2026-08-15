package com.shvoy.suppliers.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
 * The supplier-validation lifecycle (supplier remodel): readiness vs approval,
 * bank-details masking by role, and the load-bearing control — a bank-details
 * change on a VALIDATED supplier reverts it to PENDING, loudly audited.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "FINANCE")
class SupplierValidationControllerTest {

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
        for (UUID c : new UUID[] {companyA, companyB}) {
            jdbcTemplate.update("DELETE FROM supplier_audit_events WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM payments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", c);
            jdbcTemplate.update("UPDATE suppliers SET current_term_id = NULL, target_term_id = NULL WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM payment_terms WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", c);
        }
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void validationRequiresReadinessThenApproves() throws Exception {
        UUID supplierId = seedSupplier(companyA);
        // Not ready — no bank details / compliance.
        mockMvc.perform(post("/api/suppliers/{s}/validate", supplierId).header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SUPPLIER_NOT_READY_FOR_VALIDATION"));

        setBank(supplierId, "12345678");
        setCompliance(supplierId, "CONFIRMED");

        mockMvc.perform(post("/api/suppliers/{s}/validate", supplierId).header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.validationStatus").value("VALIDATED"))
            .andExpect(jsonPath("$.readyForValidation").value(true));
        assertThat(auditCount(supplierId, "VALIDATED")).isEqualTo(1);
    }

    @Test
    void changingBankDetailsRevertsAValidatedSupplierToPendingAndAuditsLoudly() throws Exception {
        UUID supplierId = seedSupplier(companyA);
        setBank(supplierId, "12345678");
        setCompliance(supplierId, "CONFIRMED");
        mockMvc.perform(post("/api/suppliers/{s}/validate", supplierId).header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(jsonPath("$.validationStatus").value("VALIDATED"));

        // The control: a changed bank account de-validates.
        mockMvc.perform(put("/api/suppliers/{s}/bank-details", supplierId).header(TENANT_HEADER, companyA).header(USER_HEADER, userAId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountName\":\"Acme\",\"accountNumber\":\"99999999\",\"sortCode\":\"11-22-33\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.validationStatus").value("PENDING"));
        assertThat(auditCount(supplierId, "BANK_CHANGE_REVERTED_VALIDATION")).isEqualTo(1);
    }

    @Test
    void bankAccountIsMaskedInTheDefaultResponseButFullOnlyToFinance() throws Exception {
        UUID supplierId = seedSupplier(companyA);
        setBank(supplierId, "12345678");

        // Default supplier read: masked (last 4 only).
        mockMvc.perform(get("/api/suppliers/{s}", supplierId).header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(jsonPath("$.bankAccountNumberMasked").value("••••5678"))
            .andExpect(jsonPath("$.bankDetailsPresent").value(true));

        // Full read is available to FINANCE.
        mockMvc.perform(get("/api/suppliers/{s}/bank-details", supplierId).header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountNumber").value("12345678"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void fullBankDetailsAreForbiddenToPurchasing() throws Exception {
        UUID supplierId = seedSupplier(companyA);
        mockMvc.perform(get("/api/suppliers/{s}/bank-details", supplierId).header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void changingCurrentTermsDoesNotMoveAnExistingPaymentsSnapshottedDueDate() throws Exception {
        // A generated PO with a balance whose due date was already snapshotted (6.2).
        UUID supplierId = seedSupplier(companyA);
        UUID poId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, 'PO-1', 'GENERATED', ?, ?, ?)",
            poId, supplierId, userAId, now, companyA);
        LocalDate fixedDue = LocalDate.of(2026, 10, 1);
        jdbcTemplate.update(
            "INSERT INTO payments (id, company_id, purchase_order_id, type, amount_amount, currency, due_date, status, created_at, anchor_event, days_offset) "
                + "VALUES (?, ?, ?, 'BALANCE', 20.00, 'USD', ?, 'PENDING', ?, 'BL', 30)",
            UUID.randomUUID(), companyA, poId, Date.valueOf(fixedDue), now);

        // Change the supplier's current terms after the fact.
        mockMvc.perform(put("/api/suppliers/{s}/payment-terms", supplierId).header(TENANT_HEADER, companyA).header(USER_HEADER, userAId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"termsType\":\"DEPOSIT_BALANCE\",\"depositPct\":40.0,\"anchorDateType\":\"ARRIVAL\",\"daysFromAnchor\":90}"))
            .andExpect(status().isOk());

        // The snapshot principle holds — the in-flight payment's due date doesn't move.
        Date due = jdbcTemplate.queryForObject(
            "SELECT due_date FROM payments WHERE purchase_order_id = ? AND type = 'BALANCE'", Date.class, poId);
        assertThat(due.toLocalDate()).isEqualTo(fixedDue);
    }

    // --- helpers ---

    private void setBank(UUID supplierId, String accountNumber) throws Exception {
        mockMvc.perform(put("/api/suppliers/{s}/bank-details", supplierId).header(TENANT_HEADER, companyA).header(USER_HEADER, userAId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountName\":\"Acme\",\"accountNumber\":\"" + accountNumber + "\",\"sortCode\":\"11-22-33\"}"))
            .andExpect(status().isOk());
    }

    private void setCompliance(UUID supplierId, String status) throws Exception {
        mockMvc.perform(put("/api/suppliers/{s}/compliance", supplierId).header(TENANT_HEADER, companyA).header(USER_HEADER, userAId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
            .andExpect(status().isOk());
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
