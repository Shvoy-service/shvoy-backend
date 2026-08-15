package com.shvoy.payments.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 6.3 — the payment queue. Read-side; seed payments directly via JDBC
 * with a spread of statuses / types / due dates, then assert the sort, the
 * derived overdue/awaiting flags, the filters, and the dashboard aggregates.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "READ_ONLY")
class PaymentQueueControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    final LocalDate today = LocalDate.now();
    UUID poAId;

    @BeforeEach
    void seed() {
        seedCompany(companyA, "Supplier A", "PO-0001");
        seedCompany(companyB, "Supplier B", "PO-0002");
        poAId = poFor(companyA);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM payments WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private final java.util.Map<UUID, UUID> poByCompany = new java.util.HashMap<>();

    private void seedCompany(UUID companyId, String supplierName, String poNumber) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyId, "Co", now);
        UUID supplierId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierId, supplierName, now, companyId);
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userId, "u-" + userId + "@example.com", now, companyId);
        UUID poId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, ?, 'GENERATED', ?, ?, ?)",
            poId, supplierId, poNumber, userId, now, companyId);
        poByCompany.put(companyId, poId);
    }

    private UUID poFor(UUID companyId) {
        return poByCompany.get(companyId);
    }

    private void seedPayment(UUID companyId, String type, String amount, LocalDate dueDate, String statusValue) {
        jdbcTemplate.update(
            "INSERT INTO payments (id, purchase_order_id, type, amount_amount, currency, due_date, status, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, 'USD', ?, ?, ?, ?)",
            UUID.randomUUID(), poFor(companyId), type, new BigDecimal(amount),
            dueDate == null ? null : Date.valueOf(dueDate), statusValue, Timestamp.from(Instant.now()), companyId);
    }

    /** Seeds A's standard spread: overdue, today, +3, +5 (boundary), +6, undated, and a paid one. */
    private void seedStandardSpread() {
        seedPayment(companyA, "DEPOSIT", "10.00", today.minusDays(2), "PENDING"); // overdue
        seedPayment(companyA, "BALANCE", "20.00", today, "PENDING");             // due today (not overdue)
        seedPayment(companyA, "BALANCE", "30.00", today.plusDays(3), "PENDING"); // due soon
        seedPayment(companyA, "BALANCE", "40.00", today.plusDays(5), "PENDING"); // day-5 boundary (in "within 5")
        seedPayment(companyA, "BALANCE", "50.00", today.plusDays(6), "PENDING"); // just outside "within 5"
        seedPayment(companyA, "BALANCE", "60.00", null, "PENDING");             // awaiting anchor date
        seedPayment(companyA, "BALANCE", "70.00", today.minusDays(10), "PAID"); // paid (excluded by default; not overdue)
    }

    // --- default view: unpaid, due-date ascending, undated last, joined rows ---

    @Test
    void defaultQueueIsUnpaidSortedByDueDateWithUndatedLastAndJoinedFields() throws Exception {
        seedStandardSpread();

        mockMvc.perform(get("/api/payments").header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(6)) // the PAID one is excluded
            .andExpect(jsonPath("$.payments.length()").value(6))
            .andExpect(jsonPath("$.payments[0].dueDate").value(today.minusDays(2).toString()))
            .andExpect(jsonPath("$.payments[0].overdue").value(true))
            .andExpect(jsonPath("$.payments[0].poReference").value("PO-0001"))
            .andExpect(jsonPath("$.payments[0].supplierName").value("Supplier A"))
            .andExpect(jsonPath("$.payments[0].amount.amount").value("10.00"))
            .andExpect(jsonPath("$.payments[1].dueDate").value(today.toString()))
            .andExpect(jsonPath("$.payments[1].overdue").value(false)) // due today is NOT overdue
            .andExpect(jsonPath("$.payments[5].awaitingDueDate").value(true)) // undated sorts last
            .andExpect(jsonPath("$.payments[5].overdue").value(false));
    }

    // --- filters ---

    @Test
    void statusFilterOverridesTheUnpaidDefault() throws Exception {
        seedStandardSpread();

        mockMvc.perform(get("/api/payments").param("status", "PAID").header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.payments[0].status").value("PAID"));
    }

    @Test
    void typeFilterNarrowsToOneType() throws Exception {
        seedStandardSpread();

        mockMvc.perform(get("/api/payments").param("type", "DEPOSIT").header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.payments[0].type").value("DEPOSIT"));
    }

    @Test
    void dueDateRangeIsInclusiveOfBothEnds() throws Exception {
        seedStandardSpread();

        // [today, today+5] — includes due-today and the day-5 boundary, excludes overdue, day-6, and undated.
        mockMvc.perform(get("/api/payments")
                .param("dueFrom", today.toString())
                .param("dueTo", today.plusDays(5).toString())
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(3));
    }

    @Test
    void overdueFilterShowsOnlyOverduePayments() throws Exception {
        seedStandardSpread();

        mockMvc.perform(get("/api/payments").param("overdue", "true").header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.payments[0].overdue").value(true));
    }

    // --- dashboard aggregates ---

    @Test
    void statsCountOverdueAndDueWithinFiveDays() throws Exception {
        seedStandardSpread();

        mockMvc.perform(get("/api/payments/stats").header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.overdueCount").value(1))              // only the -2 (the paid -10 doesn't count)
            .andExpect(jsonPath("$.dueWithin5DaysCount").value(3));      // today, +3, +5 (inclusive); +6 excluded
    }

    // --- tenancy ---

    @Test
    void theQueueOnlyShowsTheCallersCompanysPayments() throws Exception {
        seedPayment(companyA, "BALANCE", "20.00", today.plusDays(1), "PENDING");
        seedPayment(companyB, "BALANCE", "99.00", today.plusDays(1), "PENDING");

        mockMvc.perform(get("/api/payments").header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.payments[0].amount.amount").value("20.00"));
    }
}
