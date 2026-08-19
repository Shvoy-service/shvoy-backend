package com.shvoy.suppliers.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hamcrest.Matchers;
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
 * The supplier SKU read endpoint (Story — supplier SKU read endpoint).
 * Same conventions as SkuControllerTest/PriceResolutionControllerTest: no
 * class-level @Transactional, JDBC seeding, the debug tenant header. Dates
 * are seeded relative to today so the in-date/expired distinction is
 * deterministic regardless of when the suite runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupplierSkuReadControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";
    private static final String LIST_PATH = "/api/suppliers/{supplierId}/skus";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID supplierAId;
    UUID supplierBId;

    @BeforeEach
    void seedCompaniesAndSuppliers() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        supplierAId = UUID.randomUUID();
        supplierBId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierAId, "Supplier A", now, companyA);
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierBId, "Supplier B", now, companyB);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM discount_tiers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM sku_prices WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private UUID seedSku(UUID supplierId, UUID companyId, String code, String status, Integer cartonSize) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, description, status, carton_size, created_at, company_id) "
                + "VALUES (?, ?, ?, 'Widget', ?, ?, ?, ?)",
            id, supplierId, code, status, cartonSize, Timestamp.from(Instant.now()), companyId);
        return id;
    }

    private UUID seedPrice(UUID skuId, UUID companyId, String unitPriceAmount, LocalDate validFrom, LocalDate validTo) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO sku_prices (id, sku_id, unit_price_amount, currency, valid_from, valid_to, created_at, company_id) "
                + "VALUES (?, ?, ?, 'USD', ?, ?, ?, ?)",
            id, skuId, new BigDecimal(unitPriceAmount), Date.valueOf(validFrom),
            validTo == null ? null : Date.valueOf(validTo), Timestamp.from(Instant.now()), companyId);
        return id;
    }

    private void seedTier(UUID priceId, UUID companyId, int threshold, String unitPriceAmount) {
        jdbcTemplate.update(
            "INSERT INTO discount_tiers (id, sku_price_id, quantity_threshold, unit_price_amount, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), priceId, threshold, new BigDecimal(unitPriceAmount), Timestamp.from(Instant.now()), companyId);
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void returnsTheThreePriceStatesForASupplierInCodeOrder() throws Exception {
        LocalDate today = LocalDate.now();

        // SKU-A: priced and in-date (open row), with tiers.
        UUID inDateSku = seedSku(supplierAId, companyA, "SKU-A", "ACTIVE", 24);
        UUID inDatePrice = seedPrice(inDateSku, companyA, "2.0000", today.minusMonths(1), null);
        seedTier(inDatePrice, companyA, 100, "1.5000");

        // SKU-B: priced but expired (newest window ended yesterday).
        UUID expiredSku = seedSku(supplierAId, companyA, "SKU-B", "ACTIVE", null);
        seedPrice(expiredSku, companyA, "3.0000", today.minusMonths(2), today.minusDays(1));

        // SKU-C: never priced.
        seedSku(supplierAId, companyA, "SKU-C", "ACTIVE", null);

        mockMvc.perform(get(LIST_PATH, supplierAId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3))
            // ordered by code: A, B, C
            .andExpect(jsonPath("$[0].sku.code").value("SKU-A"))
            .andExpect(jsonPath("$[1].sku.code").value("SKU-B"))
            .andExpect(jsonPath("$[2].sku.code").value("SKU-C"))
            // A — in-date, carton size, current price + tier inline
            .andExpect(jsonPath("$[0].sku.cartonSize").value(24))
            .andExpect(jsonPath("$[0].currentPrice.id").value(inDatePrice.toString()))
            .andExpect(jsonPath("$[0].currentPrice.unitPrice.amount").value("2.0000"))
            .andExpect(jsonPath("$[0].currentPrice.unitPrice.currency").value("USD"))
            .andExpect(jsonPath("$[0].currentPrice.validTo").doesNotExist())
            .andExpect(jsonPath("$[0].currentPrice.inDate").value(true))
            .andExpect(jsonPath("$[0].tiers.length()").value(1))
            .andExpect(jsonPath("$[0].tiers[0].quantityThreshold").value(100))
            .andExpect(jsonPath("$[0].tiers[0].unitPrice.amount").value("1.5000"))
            // B — expired: still returned, flagged inDate:false, no tiers
            .andExpect(jsonPath("$[1].currentPrice.unitPrice.amount").value("3.0000"))
            .andExpect(jsonPath("$[1].currentPrice.inDate").value(false))
            .andExpect(jsonPath("$[1].tiers.length()").value(0))
            // C — never priced: null currentPrice, empty tiers
            .andExpect(jsonPath("$[2].currentPrice").value(Matchers.nullValue()))
            .andExpect(jsonPath("$[2].tiers.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void currentPriceIsTheLatestVersionOnASupersededTimeline() throws Exception {
        LocalDate today = LocalDate.now();
        UUID skuId = seedSku(supplierAId, companyA, "SKU-1", "ACTIVE", null);
        // Older, closed window, then the current open one.
        seedPrice(skuId, companyA, "1.0000", today.minusMonths(6), today.minusMonths(3).minusDays(1));
        UUID currentPrice = seedPrice(skuId, companyA, "2.5000", today.minusMonths(3), null);

        mockMvc.perform(get(LIST_PATH, supplierAId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].currentPrice.id").value(currentPrice.toString()))
            .andExpect(jsonPath("$[0].currentPrice.unitPrice.amount").value("2.5000"))
            .andExpect(jsonPath("$[0].currentPrice.inDate").value(true));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void tiersBelongToTheCurrentPriceOnlyNotSupersededOnes() throws Exception {
        LocalDate today = LocalDate.now();
        UUID skuId = seedSku(supplierAId, companyA, "SKU-1", "ACTIVE", null);
        UUID oldPrice = seedPrice(skuId, companyA, "1.0000", today.minusMonths(6), today.minusMonths(3).minusDays(1));
        seedTier(oldPrice, companyA, 50, "0.9000"); // tier on the superseded price — must NOT surface
        UUID currentPrice = seedPrice(skuId, companyA, "2.5000", today.minusMonths(3), null);
        seedTier(currentPrice, companyA, 200, "2.0000");

        mockMvc.perform(get(LIST_PATH, supplierAId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].tiers.length()").value(1))
            .andExpect(jsonPath("$[0].tiers[0].quantityThreshold").value(200))
            .andExpect(jsonPath("$[0].tiers[0].unitPrice.amount").value("2.0000"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void excludesInactiveSkus() throws Exception {
        LocalDate today = LocalDate.now();
        UUID activeSku = seedSku(supplierAId, companyA, "SKU-ACTIVE", "ACTIVE", null);
        seedPrice(activeSku, companyA, "2.0000", today.minusMonths(1), null);
        seedSku(supplierAId, companyA, "SKU-INACTIVE", "INACTIVE", null);

        mockMvc.perform(get(LIST_PATH, supplierAId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].sku.code").value("SKU-ACTIVE"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void doesNotLeakAnotherSuppliersSkus() throws Exception {
        LocalDate today = LocalDate.now();
        UUID mineSku = seedSku(supplierAId, companyA, "MINE", "ACTIVE", null);
        seedPrice(mineSku, companyA, "2.0000", today.minusMonths(1), null);
        // A SKU on supplier B (a different company) must not appear under supplier A.
        seedSku(supplierBId, companyB, "THEIRS", "ACTIVE", null);

        mockMvc.perform(get(LIST_PATH, supplierAId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].sku.code").value("MINE"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void crossTenantSupplierReturnsNotFound() throws Exception {
        mockMvc.perform(get(LIST_PATH, supplierAId)
                .header(TENANT_HEADER, companyB.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void emptyWhenSupplierHasNoSkus() throws Exception {
        mockMvc.perform(get(LIST_PATH, supplierAId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }
}
