package com.shvoy.suppliers.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

/**
 * Story 9.2 — price-expiry warnings. The per-supplier rollup derived (at read
 * time) from 3.8's resolver, the 14-day boundary, EXPIRED dominance, the
 * lapsed-vs-never-priced distinction, open-ended-never-warns, the
 * upload-clears-warning flow end to end, and the dashboard integration +
 * anti-drift (dashboard section == the standalone operation).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = {"ADMIN", "PURCHASING"})
class PriceWarningControllerTest {

    private static final String TENANT = "X-Debug-Company-Id";
    private static final String USER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    final LocalDate today = LocalDate.now();
    UUID userAId;
    UUID supplierA;
    UUID supplierB;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "pw-" + userAId + "@x.com", now, companyA);
        supplierA = insertSupplier(companyA, "Acme", "ACTIVE");
        supplierB = insertSupplier(companyB, "Beta", "ACTIVE");
    }

    @AfterEach
    void cleanUp() {
        for (UUID c : new UUID[] {companyA, companyB}) {
            jdbcTemplate.update("DELETE FROM discount_tiers WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM sku_prices WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM skus WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM users WHERE company_id = ?", c);
        }
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    // --- boundary: expires today = in-date; today+14 = expiring; today+15 = not ---

    @Test
    void aPriceExpiringTodayIsInDateButWarnsAsExpiringSoon() throws Exception {
        UUID sku = insertSku(supplierA, "SKU-1", companyA);
        insertPrice(sku, "10.0000", today.minusDays(30), today, companyA); // valid_to == today

        warnings().andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].status").value("EXPIRING_SOON"))
            .andExpect(jsonPath("$[0].expiringCount").value(1))
            .andExpect(jsonPath("$[0].earliestExpiry").value(today.toString()));
    }

    @Test
    void aPriceExpiringInFourteenDaysWarnsButFifteenDoesNot() throws Exception {
        UUID skuEdge = insertSku(supplierA, "SKU-14", companyA);
        insertPrice(skuEdge, "10.0000", today.minusDays(1), today.plusDays(14), companyA); // boundary: warns
        UUID skuSafe = insertSku(supplierA, "SKU-15", companyA);
        insertPrice(skuSafe, "10.0000", today.minusDays(1), today.plusDays(15), companyA); // just outside: safe

        warnings().andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].status").value("EXPIRING_SOON"))
            .andExpect(jsonPath("$[0].expiringCount").value(1)); // only the day-14 SKU
    }

    @Test
    void aLapsedPriceIsExpired() throws Exception {
        UUID sku = insertSku(supplierA, "SKU-1", companyA);
        insertPrice(sku, "10.0000", today.minusDays(60), today.minusDays(1), companyA); // ended yesterday

        warnings().andExpect(jsonPath("$[0].status").value("EXPIRED"))
            .andExpect(jsonPath("$[0].expiredCount").value(1))
            .andExpect(jsonPath("$[0].neverPricedCount").value(0));
    }

    // --- never-priced vs lapsed, distinguishable ---

    @Test
    void aNeverPricedSkuIsExpiredAndDistinguishedFromLapsed() throws Exception {
        UUID lapsed = insertSku(supplierA, "SKU-LAPSED", companyA);
        insertPrice(lapsed, "10.0000", today.minusDays(60), today.minusDays(1), companyA);
        insertSku(supplierA, "SKU-NEVER", companyA); // no price row at all

        warnings().andExpect(jsonPath("$[0].status").value("EXPIRED"))
            .andExpect(jsonPath("$[0].expiredCount").value(2))
            .andExpect(jsonPath("$[0].neverPricedCount").value(1)); // the subset never priced

        UUID neverSku = jdbcTemplate.queryForObject(
            "SELECT id FROM skus WHERE code = 'SKU-NEVER' AND company_id = ?", UUID.class, companyA);
        // Drill-down distinguishes the two cases per SKU.
        String detail = mockMvc.perform(get("/api/suppliers/{id}/price-warnings", supplierA).header(TENANT, companyA.toString()))
            .andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("NEVER_PRICED").contains("LAPSED");
        assertThat(reasonForSku(detail, neverSku)).isEqualTo("NEVER_PRICED");
    }

    // --- EXPIRED dominates ---

    @Test
    void expiredDominatesExpiringInTheRollup() throws Exception {
        UUID expired = insertSku(supplierA, "SKU-EXP", companyA);
        insertPrice(expired, "10.0000", today.minusDays(60), today.minusDays(1), companyA);
        UUID expiring = insertSku(supplierA, "SKU-SOON", companyA);
        insertPrice(expiring, "10.0000", today.minusDays(1), today.plusDays(5), companyA);

        warnings().andExpect(jsonPath("$[0].status").value("EXPIRED")) // not EXPIRING_SOON
            .andExpect(jsonPath("$[0].expiredCount").value(1))
            .andExpect(jsonPath("$[0].expiringCount").value(1))
            .andExpect(jsonPath("$[0].earliestExpiry").doesNotExist()); // moot for an expired supplier
    }

    // --- open-ended never warns; inactive excluded ---

    @Test
    void anOpenEndedPriceNeverWarns() throws Exception {
        UUID sku = insertSku(supplierA, "SKU-1", companyA);
        insertPrice(sku, "10.0000", today.minusDays(30), null, companyA); // valid_to null → 永 in-date

        warnings().andExpect(jsonPath("$.length()").value(0)); // nothing to warn about
    }

    @Test
    void inactiveSkusAndSuppliersDoNotWarn() throws Exception {
        UUID inactiveSku = insertSku(supplierA, "SKU-DEAD", companyA);
        jdbcTemplate.update("UPDATE skus SET status = 'INACTIVE' WHERE id = ?", inactiveSku);
        insertPrice(inactiveSku, "10.0000", today.minusDays(60), today.minusDays(1), companyA); // lapsed, but SKU dead

        UUID inactiveSupplier = insertSupplier(companyA, "Dormant", "INACTIVE");
        UUID skuOfInactive = insertSku(inactiveSupplier, "SKU-Z", companyA);
        insertPrice(skuOfInactive, "10.0000", today.minusDays(60), today.minusDays(1), companyA);

        warnings().andExpect(jsonPath("$.length()").value(0)); // dead rows don't nag
    }

    // --- derived at read time: upload a superseding price, warning clears ---

    @Test
    void uploadingAFreshPriceClearsTheWarningOnTheNextRead() throws Exception {
        UUID sku = insertSku(supplierA, "SKU-1", companyA);
        insertPrice(sku, "10.0000", today.minusDays(60), today.minusDays(1), companyA); // lapsed → EXPIRED
        warnings().andExpect(jsonPath("$[0].status").value("EXPIRED"));

        // Log a fresh, open-ended price through the real endpoint — no job, no cache.
        mockMvc.perform(post("/api/suppliers/{sid}/skus/{skuId}/prices", supplierA, sku)
                .header(TENANT, companyA.toString()).header(USER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitPriceAmount\":12.0000,\"currency\":\"USD\",\"validFrom\":\"" + today + "\"}"))
            .andExpect(status().isCreated());

        warnings().andExpect(jsonPath("$.length()").value(0)); // gone on the very next read
    }

    // --- dashboard integration + anti-drift ---

    @Test
    void theDashboardCarriesTheSameWarningsAsTheStandaloneOperation() throws Exception {
        UUID sku = insertSku(supplierA, "SKU-1", companyA);
        insertPrice(sku, "10.0000", today.minusDays(30), today.plusDays(3), companyA); // expiring soon

        String standalone = mockMvc.perform(get("/api/suppliers/price-warnings").header(TENANT, companyA.toString()))
            .andReturn().getResponse().getContentAsString();
        String dashboard = mockMvc.perform(get("/api/dashboard").header(TENANT, companyA.toString()))
            .andReturn().getResponse().getContentAsString();

        // Anti-drift: the dashboard's priceWarnings equal the standalone operation's (same tripwire as 9.1).
        assertThat((String) JsonPath.read(dashboard, "$.priceWarnings[0].supplierId"))
            .isEqualTo(JsonPath.read(standalone, "$[0].supplierId"));
        assertThat((String) JsonPath.read(dashboard, "$.priceWarnings[0].status"))
            .isEqualTo(JsonPath.read(standalone, "$[0].status"));
        assertThat((int) JsonPath.read(dashboard, "$.priceWarnings.length()")).isEqualTo(1);
    }

    @Test
    void theDashboardDigestIsCappedAndExpiredFirst() throws Exception {
        // 12 suppliers expiring + 1 expired → the dashboard shows 10, expired first.
        UUID expiredSupplier = insertSupplier(companyA, "Zzz Expired", "ACTIVE");
        UUID es = insertSku(expiredSupplier, "SKU-E", companyA);
        insertPrice(es, "10.0000", today.minusDays(60), today.minusDays(1), companyA);
        for (int i = 0; i < 12; i++) {
            UUID sup = insertSupplier(companyA, "Sup-" + i, "ACTIVE");
            UUID sk = insertSku(sup, "SKU-" + i, companyA);
            insertPrice(sk, "10.0000", today.minusDays(1), today.plusDays(1 + i), companyA); // all expiring
        }

        String dashboard = mockMvc.perform(get("/api/dashboard").header(TENANT, companyA.toString()))
            .andReturn().getResponse().getContentAsString();
        assertThat((int) JsonPath.read(dashboard, "$.priceWarnings.length()")).isEqualTo(10); // capped
        assertThat((String) JsonPath.read(dashboard, "$.priceWarnings[0].status")).isEqualTo("EXPIRED"); // expired first
    }

    // --- tenancy ---

    @Test
    void warningsAreTenantScoped() throws Exception {
        UUID skuB = insertSku(supplierB, "SKU-B", companyB);
        insertPrice(skuB, "10.0000", today.minusDays(60), today.minusDays(1), companyB); // B has an expired file

        // Company A (no warnings of its own) sees none of B's.
        warnings().andExpect(jsonPath("$.length()").value(0));
    }

    // --- driving ---

    private org.springframework.test.web.servlet.ResultActions warnings() throws Exception {
        return mockMvc.perform(get("/api/suppliers/price-warnings").header(TENANT, companyA.toString()))
            .andExpect(status().isOk());
    }

    private String reasonForSku(String detailJson, UUID skuId) {
        java.util.List<java.util.Map<String, Object>> rows = JsonPath.read(detailJson, "$");
        return rows.stream().filter(r -> skuId.toString().equals(r.get("skuId")))
            .map(r -> (String) r.get("reason")).findFirst().orElse(null);
    }

    // --- seeding ---

    private UUID insertSupplier(UUID company, String name, String statusValue) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, ?, ?, ?)",
            id, name, statusValue, Timestamp.from(Instant.now()), company);
        return id;
    }

    private UUID insertSku(UUID supplier, String code, UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, description, status, created_at, company_id) "
                + "VALUES (?, ?, ?, 'Widget', 'ACTIVE', ?, ?)",
            id, supplier, code, Timestamp.from(Instant.now()), company);
        return id;
    }

    private void insertPrice(UUID sku, String amount, LocalDate validFrom, LocalDate validTo, UUID company) {
        jdbcTemplate.update(
            "INSERT INTO sku_prices (id, sku_id, unit_price_amount, currency, valid_from, valid_to, created_at, company_id) "
                + "VALUES (?, ?, ?, 'USD', ?, ?, ?, ?)",
            UUID.randomUUID(), sku, new BigDecimal(amount), Date.valueOf(validFrom),
            validTo == null ? null : Date.valueOf(validTo), Timestamp.from(Instant.now()), company);
    }
}
