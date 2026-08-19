package com.shvoy.dashboard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
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

import com.jayway.jsonpath.JsonPath;

/**
 * Story 9.1 — the dashboard aggregation endpoint. This is an assembly, so the
 * tests are about the composition and the contract, not new maths. The
 * centrepiece is the <strong>anti-drift</strong> check: each stat is read via the
 * underlying operation ({@code /api/payments/stats}, {@code /api/discrepancies/stats})
 * and via {@code /api/dashboard}, and asserted equal — the tripwire for the day
 * someone gives the dashboard its own query and the landing page starts
 * disagreeing with the payments screen.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "READ_ONLY") // the dashboard is everyone's landing page
class DashboardControllerTest {

    private static final String TENANT = "X-Debug-Company-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    final LocalDate today = LocalDate.now();
    UUID userAId;
    UUID poA;
    UUID poB;

    @BeforeEach
    void seed() {
        userAId = seedCompany(companyA, "Acme Imports", "PO-A1");
        poA = poByCompany;
        seedCompany(companyB, "Other Co", "PO-B1");
        poB = poByCompany;
    }

    @AfterEach
    void cleanUp() {
        for (UUID c : new UUID[] {companyA, companyB}) {
            jdbcTemplate.update("DELETE FROM discrepancy_case_audit_events WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM discrepancy_cases WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM payments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM users WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", c);
        }
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    // --- the anti-drift check ---

    @Test
    void everyStatMatchesItsUnderlyingOperationExactly() throws Exception {
        seedStandardSpread(companyA, poA);
        seedOpenDiscrepancy(companyA, poA);
        seedOpenDiscrepancy(companyA, poA);

        String dash = getJson("/api/dashboard").getResponse().getContentAsString();
        String paymentStats = getJson("/api/payments/stats").getResponse().getContentAsString();
        String discrepancyStats = getJson("/api/discrepancies/stats").getResponse().getContentAsString();

        // Computed two ways, asserted identical — the tripwire against a divergent second query.
        assertThat((int) JsonPath.read(dash, "$.stats.overduePayments"))
            .isEqualTo((int) (Integer) JsonPath.read(paymentStats, "$.overdueCount"));
        assertThat((int) JsonPath.read(dash, "$.stats.dueWithinFiveDays"))
            .isEqualTo((int) (Integer) JsonPath.read(paymentStats, "$.dueWithin5DaysCount"));
        assertThat((int) JsonPath.read(dash, "$.stats.openDiscrepancies"))
            .isEqualTo((int) (Integer) JsonPath.read(discrepancyStats, "$.openCaseCount"));
    }

    @Test
    void theOverdueFlagOnRowsAgreesWithTheOverdueStat() throws Exception {
        seedStandardSpread(companyA, poA);

        mockMvc.perform(get("/api/dashboard").header(TENANT, companyA.toString()))
            .andExpect(status().isOk())
            // The spread seeds exactly one overdue payment; the stat and the flagged rows must agree.
            .andExpect(jsonPath("$.stats.overduePayments").value(1))
            .andExpect(jsonPath("$.payments[?(@.overdue == true)]", hasSize(1)));
    }

    // --- rows: cap, ordering, undated-last ---

    @Test
    void rowsAreCappedAtTenSoonestDueFirstUndatedLast() throws Exception {
        // 12 dated (ascending) + 2 undated → 14 unpaid; the digest shows the 10 soonest-due, undated grouped last.
        for (int i = 1; i <= 12; i++) {
            seedPayment(companyA, poA, "BALANCE", "10.00", today.plusDays(i), "PENDING");
        }
        seedPayment(companyA, poA, "BALANCE", "10.00", null, "PENDING");
        seedPayment(companyA, poA, "BALANCE", "10.00", null, "PENDING");

        String dash = getJson("/api/dashboard").getResponse().getContentAsString();
        assertThat((java.util.List<?>) JsonPath.read(dash, "$.payments")).hasSize(10); // cap
        // First row is the soonest due (today+1); the 10 shown are all dated (undated sort after, off the page).
        assertThat((String) JsonPath.read(dash, "$.payments[0].dueDate")).isEqualTo(today.plusDays(1).toString());
        assertThat((String) JsonPath.read(dash, "$.payments[9].dueDate")).isEqualTo(today.plusDays(10).toString());
    }

    @Test
    void undatedRowsSortAfterDatedOnes() throws Exception {
        seedPayment(companyA, poA, "BALANCE", "10.00", null, "PENDING");        // undated
        seedPayment(companyA, poA, "DEPOSIT", "5.00", today.plusDays(3), "PENDING"); // dated

        String dash = getJson("/api/dashboard").getResponse().getContentAsString();
        assertThat((String) JsonPath.read(dash, "$.payments[0].dueDate")).isEqualTo(today.plusDays(3).toString());
        assertThat((Object) JsonPath.read(dash, "$.payments[1].dueDate")).isNull(); // undated last
    }

    @Test
    void paidPaymentsAreExcludedFromTheDigest() throws Exception {
        seedPayment(companyA, poA, "BALANCE", "10.00", today.plusDays(2), "PAID");
        seedPayment(companyA, poA, "DEPOSIT", "5.00", today.plusDays(1), "PENDING");

        String dash = getJson("/api/dashboard").getResponse().getContentAsString();
        assertThat((java.util.List<?>) JsonPath.read(dash, "$.payments")).hasSize(1); // only the unpaid one
    }

    // --- shape / empty state / alerts slot ---

    @Test
    void theResponseShapeMatchesTheContract() throws Exception {
        seedPayment(companyA, poA, "DEPOSIT", "12500.00", today.plusDays(2), "PENDING");

        mockMvc.perform(get("/api/dashboard").header(TENANT, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.*", hasSize(4))) // stats, payments, priceWarnings, alerts
            .andExpect(jsonPath("$.stats.overduePayments").exists())
            .andExpect(jsonPath("$.stats.dueWithinFiveDays").exists())
            .andExpect(jsonPath("$.stats.openDiscrepancies").exists())
            .andExpect(jsonPath("$.stats.*", hasSize(3)))
            .andExpect(jsonPath("$.payments[0].poReference").value("PO-A1"))
            .andExpect(jsonPath("$.payments[0].supplierName").value("Acme Imports"))
            .andExpect(jsonPath("$.payments[0].type").value("DEPOSIT"))
            .andExpect(jsonPath("$.payments[0].amount.amount").value("12500.00"))
            .andExpect(jsonPath("$.payments[0].amount.currency").value("USD"))
            .andExpect(jsonPath("$.payments[0].dueDate").value(today.plusDays(2).toString()))
            .andExpect(jsonPath("$.payments[0].status").value("PENDING"))
            .andExpect(jsonPath("$.payments[0].overdue").value(false))
            .andExpect(jsonPath("$.payments[0].*", hasSize(7)))
            .andExpect(jsonPath("$.priceWarnings").isArray())
            .andExpect(jsonPath("$.priceWarnings", hasSize(0))) // 9.2: no expiring prices seeded here
            .andExpect(jsonPath("$.alerts").isArray())
            .andExpect(jsonPath("$.alerts", hasSize(0))); // 9.3's slot, empty from day one
    }

    @Test
    void aNewCompanyGetsZeroesAndEmptyRowsNotAnError() throws Exception {
        // companyA seeded but with no payments/discrepancies.
        mockMvc.perform(get("/api/dashboard").header(TENANT, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stats.overduePayments").value(0))
            .andExpect(jsonPath("$.stats.dueWithinFiveDays").value(0))
            .andExpect(jsonPath("$.stats.openDiscrepancies").value(0))
            .andExpect(jsonPath("$.payments", hasSize(0)))
            .andExpect(jsonPath("$.priceWarnings", hasSize(0)))
            .andExpect(jsonPath("$.alerts", hasSize(0)));
    }

    // --- access / tenancy ---

    @Test
    @WithMockUser(roles = "FINANCE")
    void everyRoleCanReadTheDashboard() throws Exception {
        mockMvc.perform(get("/api/dashboard").header(TENANT, companyA.toString()))
            .andExpect(status().isOk());
    }

    @Test
    void theComposedResponseContainsNothingFromAnotherTenant() throws Exception {
        seedStandardSpread(companyA, poA);
        seedOpenDiscrepancy(companyA, poA);
        // Company B has its own, larger spread that must never leak into A's dashboard.
        for (int i = 0; i < 5; i++) {
            seedPayment(companyB, poB, "BALANCE", "99.00", today.minusDays(1), "PENDING"); // all overdue
        }
        seedOpenDiscrepancy(companyB, poB);
        seedOpenDiscrepancy(companyB, poB);

        String dash = getJson("/api/dashboard").getResponse().getContentAsString(); // as company A
        // A's own numbers, none of B's: A has 1 overdue + 1 open case; B's 5 overdue / 2 cases are absent.
        assertThat((int) JsonPath.read(dash, "$.stats.overduePayments")).isEqualTo(1);
        assertThat((int) JsonPath.read(dash, "$.stats.openDiscrepancies")).isEqualTo(1);
        // No row carries B's supplier or amount.
        assertThat(dash).doesNotContain("Other Co").doesNotContain("99.00");
    }

    // --- driving ---

    private org.springframework.test.web.servlet.MvcResult getJson(String path) throws Exception {
        return mockMvc.perform(get(path).header(TENANT, companyA.toString()))
            .andExpect(status().isOk()).andReturn();
    }

    // --- seeding ---

    private UUID poByCompany;

    private UUID seedCompany(UUID companyId, String supplierName, String poNumber) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyId, "Co", now);
        UUID supplierId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, validation_status, created_at, company_id) VALUES (?, ?, 'ACTIVE', 'VALIDATED', ?, ?)",
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
        poByCompany = poId;
        return userId;
    }

    private void seedPayment(UUID companyId, UUID poId, String type, String amount, LocalDate dueDate, String statusValue) {
        jdbcTemplate.update(
            "INSERT INTO payments (id, purchase_order_id, type, amount_amount, currency, due_date, status, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, 'USD', ?, ?, ?, ?)",
            UUID.randomUUID(), poId, type, new BigDecimal(amount),
            dueDate == null ? null : Date.valueOf(dueDate), statusValue, Timestamp.from(Instant.now()), companyId);
    }

    /** One overdue, one due today, one +3, one +5 (boundary), one +6, one undated, one paid. */
    private void seedStandardSpread(UUID companyId, UUID poId) {
        seedPayment(companyId, poId, "DEPOSIT", "10.00", today.minusDays(2), "PENDING"); // overdue
        seedPayment(companyId, poId, "BALANCE", "20.00", today, "PENDING");             // due today (not overdue)
        seedPayment(companyId, poId, "BALANCE", "30.00", today.plusDays(3), "PENDING"); // within 5
        seedPayment(companyId, poId, "BALANCE", "40.00", today.plusDays(5), "PENDING"); // within 5 (boundary)
        seedPayment(companyId, poId, "BALANCE", "50.00", today.plusDays(6), "PENDING"); // outside 5
        seedPayment(companyId, poId, "BALANCE", "60.00", null, "PENDING");             // undated
        seedPayment(companyId, poId, "BALANCE", "70.00", today.minusDays(1), "PAID");   // paid (not overdue)
    }

    private void seedOpenDiscrepancy(UUID companyId, UUID poId) {
        UUID paymentId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO payments (id, purchase_order_id, type, amount_amount, currency, status, created_at, company_id) "
                + "VALUES (?, ?, 'BALANCE', 100.00, 'USD', 'BLOCKED', ?, ?)",
            paymentId, poId, Timestamp.from(Instant.now()), companyId);
        jdbcTemplate.update(
            "INSERT INTO discrepancy_cases (id, company_id, payment_id, purchase_order_id, status, failure_detail, created_at) "
                + "VALUES (?, ?, ?, ?, 'OPEN', 'seeded', ?)",
            UUID.randomUUID(), companyId, paymentId, poId, Timestamp.from(Instant.now()));
    }
}
