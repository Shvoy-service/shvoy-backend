package com.shvoy.dashboard.controller;

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
 * Story 9.3 — the system-alert banner. Two read-time conditions, each appearing
 * and clearing through a real state change; empty when healthy; ordered;
 * tenant-scoped. {@code READ_ONLY} throughout — the banner is visible to every
 * role.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "READ_ONLY")
class AlertControllerTest {

    private static final String TENANT = "X-Debug-Company-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
    }

    @AfterEach
    void cleanUp() {
        for (UUID c : new UUID[] {companyA, companyB}) {
            jdbcTemplate.update("DELETE FROM approver_pool_members WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM approver_pool_settings WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM users WHERE company_id = ?", c);
        }
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    // --- approver pool ---

    @Test
    void thePoolAlertAppearsWhenStrandedAndClearsWhenToppedUp() throws Exception {
        UUID a1 = approver(companyA, "ACTIVE");
        UUID a2 = approver(companyA, "ACTIVE");
        poolMember(companyA, a1);
        poolMember(companyA, a2);
        requiredCount(companyA, 2);

        alerts(companyA).andExpect(jsonPath("$.alerts", org.hamcrest.Matchers.hasSize(0))); // 2 of 2 — satisfied

        // Deactivate one member's user → eligible 1 < required 2 → stranded.
        jdbcTemplate.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", a2);
        alerts(companyA)
            .andExpect(jsonPath("$.alerts[?(@.code == 'APPROVER_POOL_UNSATISFIABLE')]", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.alerts[0].severity").value("WARNING"))
            .andExpect(jsonPath("$.alerts[0].link").value("/settings/approvers"));

        // Reactivate → back to 2 of 2 → gone.
        jdbcTemplate.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", a2);
        alerts(companyA).andExpect(jsonPath("$.alerts", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    void aCompanyWithNoApproversIsNotNagged() throws Exception {
        // Default required is 2, but zero configured approvers is "not set up", not a stranded pool.
        requiredCount(companyA, 2);
        alerts(companyA).andExpect(jsonPath("$.alerts", org.hamcrest.Matchers.hasSize(0)));
    }

    // --- supplier re-validation ---

    @Test
    void theSupplierAlertAppearsOnRevertWithALiveOrderAndClearsOnRevalidate() throws Exception {
        UUID supplier = supplier(companyA, "Acme", "VALIDATED");
        purchaseOrder(companyA, supplier, "GENERATED"); // a live order

        alerts(companyA).andExpect(jsonPath("$.alerts", org.hamcrest.Matchers.hasSize(0))); // validated — nothing to warn

        // Bank details changed → reverted to PENDING (with a live order outstanding).
        jdbcTemplate.update("UPDATE suppliers SET validation_status = 'PENDING' WHERE id = ?", supplier);
        alerts(companyA)
            .andExpect(jsonPath("$.alerts[?(@.code == 'SUPPLIER_REVALIDATION_REQUIRED')]", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.alerts[0].link").value("/suppliers?validationStatus=PENDING"));

        // Re-validated → gone.
        jdbcTemplate.update("UPDATE suppliers SET validation_status = 'VALIDATED' WHERE id = ?", supplier);
        alerts(companyA).andExpect(jsonPath("$.alerts", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    void aPendingSupplierWithNoLiveOrderDoesNotAlert() throws Exception {
        UUID supplier = supplier(companyA, "Acme", "PENDING");
        purchaseOrder(companyA, supplier, "DRAFT"); // not a live order

        alerts(companyA).andExpect(jsonPath("$.alerts", org.hamcrest.Matchers.hasSize(0)));
    }

    // --- healthy, ordering, shape ---

    @Test
    void aHealthyCompanyHasEmptyAlerts() throws Exception {
        UUID supplier = supplier(companyA, "Acme", "VALIDATED");
        purchaseOrder(companyA, supplier, "SENT");
        UUID a1 = approver(companyA, "ACTIVE");
        poolMember(companyA, a1);
        requiredCount(companyA, 1);

        alerts(companyA).andExpect(jsonPath("$.alerts", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    void bothAlertsAtOnceAreOrderedAndMatchTheShape() throws Exception {
        // Stranded pool + a reverted supplier with a live order.
        UUID a1 = approver(companyA, "ACTIVE");
        poolMember(companyA, a1);
        requiredCount(companyA, 2); // 1 of 2 → stranded
        UUID supplier = supplier(companyA, "Acme", "PENDING");
        purchaseOrder(companyA, supplier, "GENERATED");

        alerts(companyA)
            .andExpect(jsonPath("$.alerts", org.hamcrest.Matchers.hasSize(2)))
            // Both WARNING, so ordered by code: APPROVER_POOL_UNSATISFIABLE before SUPPLIER_REVALIDATION_REQUIRED.
            .andExpect(jsonPath("$.alerts[0].code").value("APPROVER_POOL_UNSATISFIABLE"))
            .andExpect(jsonPath("$.alerts[1].code").value("SUPPLIER_REVALIDATION_REQUIRED"))
            // The pinned entry shape: exactly code, severity, message, link.
            .andExpect(jsonPath("$.alerts[0].*", org.hamcrest.Matchers.hasSize(4)))
            .andExpect(jsonPath("$.alerts[0].code").exists())
            .andExpect(jsonPath("$.alerts[0].severity").value("WARNING"))
            .andExpect(jsonPath("$.alerts[0].message").exists())
            .andExpect(jsonPath("$.alerts[0].link").exists());
    }

    // --- tenancy ---

    @Test
    void alertsAreEvaluatedWithinTheCallersCompanyOnly() throws Exception {
        // Company B is unhealthy on both counts.
        UUID b1 = approver(companyB, "ACTIVE");
        poolMember(companyB, b1);
        requiredCount(companyB, 2);
        UUID supB = supplier(companyB, "Beta", "PENDING");
        purchaseOrder(companyB, supB, "GENERATED");

        // Company A (healthy — nothing seeded) sees none of B's.
        alerts(companyA).andExpect(jsonPath("$.alerts", org.hamcrest.Matchers.hasSize(0)));
    }

    // --- driving ---

    private ResultActions alerts(UUID company) throws Exception {
        return mockMvc.perform(get("/api/dashboard").header(TENANT, company.toString()))
            .andExpect(status().isOk());
    }

    // --- seeding ---

    private UUID approver(UUID company, String userStatus) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'APPROVER', ?, ?, ?)",
            id, "appr-" + id + "@x.com", userStatus, Timestamp.from(Instant.now()), company);
        return id;
    }

    private void poolMember(UUID company, UUID userId) {
        jdbcTemplate.update(
            "INSERT INTO approver_pool_members (id, user_id, created_at, company_id) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(), userId, Timestamp.from(Instant.now()), company);
    }

    private void requiredCount(UUID company, int n) {
        jdbcTemplate.update(
            "INSERT INTO approver_pool_settings (id, required_sign_off_count, created_at, company_id) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(), n, Timestamp.from(Instant.now()), company);
    }

    private UUID supplier(UUID company, String name, String validationStatus) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, validation_status, created_at, company_id) "
                + "VALUES (?, ?, 'ACTIVE', ?, ?, ?)",
            id, name, validationStatus, Timestamp.from(Instant.now()), company);
        return id;
    }

    private void purchaseOrder(UUID company, UUID supplier, String statusValue) {
        UUID user = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            user, "po-" + user + "@x.com", Timestamp.from(Instant.now()), company);
        UUID po = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            po, supplier, "PO-" + po, statusValue, user, Timestamp.from(Instant.now()), company);
    }
}
