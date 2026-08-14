package com.shvoy.suppliers.controller;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.shvoy.LogCapture;
import com.shvoy.suppliers.service.PriceResolutionService;

/**
 * Same conventions as DiscountTierControllerTest/SkuControllerTest: no
 * class-level @Transactional, JDBC seeding, the debug tenant header.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PriceResolutionControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";
    private static final String RESOLVE_PATH = "/api/suppliers/{supplierId}/skus/{skuId}/price-resolution";

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

    private UUID seedSku(UUID supplierId, UUID companyId, Integer cartonSize) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, status, carton_size, created_at, company_id) "
                + "VALUES (?, ?, 'SKU-1', 'ACTIVE', ?, ?, ?)",
            id, supplierId, cartonSize, Timestamp.from(Instant.now()), companyId);
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
    void resolvesBasePriceWhenQuantityBelowAllTiers() throws Exception {
        UUID skuId = seedSku(supplierAId, companyA, null);
        UUID priceId = seedPrice(skuId, companyA, "2.0000", LocalDate.of(2026, 1, 1), null);
        seedTier(priceId, companyA, 100, "1.5000");

        mockMvc.perform(get(RESOLVE_PATH, supplierAId, skuId)
                .header(TENANT_HEADER, companyA.toString())
                .param("quantity", "50")
                .param("asOfDate", "2026-06-01"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.priceFound").value(true))
            .andExpect(jsonPath("$.skuPriceId").value(priceId.toString()))
            .andExpect(jsonPath("$.unitPrice.amount").value("2.0000"))
            .andExpect(jsonPath("$.appliedTierThreshold").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void resolvesTierPriceWhenQuantityMeetsThreshold() throws Exception {
        UUID skuId = seedSku(supplierAId, companyA, null);
        UUID priceId = seedPrice(skuId, companyA, "2.0000", LocalDate.of(2026, 1, 1), null);
        seedTier(priceId, companyA, 100, "1.5000");
        seedTier(priceId, companyA, 500, "1.0000");

        mockMvc.perform(get(RESOLVE_PATH, supplierAId, skuId)
                .header(TENANT_HEADER, companyA.toString())
                .param("quantity", "150")
                .param("asOfDate", "2026-06-01"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.priceFound").value(true))
            .andExpect(jsonPath("$.unitPrice.amount").value("1.5000"))
            .andExpect(jsonPath("$.appliedTierThreshold").value(100));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void resolvesTheHighestApplicableTierNotJustAnyMatchingOne() throws Exception {
        UUID skuId = seedSku(supplierAId, companyA, null);
        UUID priceId = seedPrice(skuId, companyA, "2.0000", LocalDate.of(2026, 1, 1), null);
        seedTier(priceId, companyA, 100, "1.5000");
        seedTier(priceId, companyA, 500, "1.0000");

        mockMvc.perform(get(RESOLVE_PATH, supplierAId, skuId)
                .header(TENANT_HEADER, companyA.toString())
                .param("quantity", "1000")
                .param("asOfDate", "2026-06-01"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unitPrice.amount").value("1.0000"))
            .andExpect(jsonPath("$.appliedTierThreshold").value(500));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void resolvesTheHistoricalPriceForASupersededWindowRatherThanTheCurrentOne() throws Exception {
        UUID skuId = seedSku(supplierAId, companyA, null);
        UUID oldPriceId = seedPrice(skuId, companyA, "1.0000", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28));
        seedPrice(skuId, companyA, "2.0000", LocalDate.of(2026, 3, 1), null);

        mockMvc.perform(get(RESOLVE_PATH, supplierAId, skuId)
                .header(TENANT_HEADER, companyA.toString())
                .param("quantity", "10")
                .param("asOfDate", "2026-01-15"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.priceFound").value(true))
            .andExpect(jsonPath("$.skuPriceId").value(oldPriceId.toString()))
            .andExpect(jsonPath("$.unitPrice.amount").value("1.0000"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void returnsNoValidPriceWhenNoWindowCoversTheDate() throws Exception {
        UUID skuId = seedSku(supplierAId, companyA, null);
        seedPrice(skuId, companyA, "2.0000", LocalDate.of(2026, 3, 1), null);

        mockMvc.perform(get(RESOLVE_PATH, supplierAId, skuId)
                .header(TENANT_HEADER, companyA.toString())
                .param("quantity", "10")
                .param("asOfDate", "2026-01-01"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.priceFound").value(false))
            .andExpect(jsonPath("$.skuPriceId").doesNotExist())
            .andExpect(jsonPath("$.unitPrice").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void rejectsZeroQuantity() throws Exception {
        UUID skuId = seedSku(supplierAId, companyA, null);
        seedPrice(skuId, companyA, "2.0000", LocalDate.of(2026, 1, 1), null);

        mockMvc.perform(get(RESOLVE_PATH, supplierAId, skuId)
                .header(TENANT_HEADER, companyA.toString())
                .param("quantity", "0")
                .param("asOfDate", "2026-06-01"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void rejectsNegativeQuantity() throws Exception {
        UUID skuId = seedSku(supplierAId, companyA, null);
        seedPrice(skuId, companyA, "2.0000", LocalDate.of(2026, 1, 1), null);

        mockMvc.perform(get(RESOLVE_PATH, supplierAId, skuId)
                .header(TENANT_HEADER, companyA.toString())
                .param("quantity", "-5")
                .param("asOfDate", "2026-06-01"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void nullCartonSizeAlwaysReportsValid() throws Exception {
        UUID skuId = seedSku(supplierAId, companyA, null);
        seedPrice(skuId, companyA, "2.0000", LocalDate.of(2026, 1, 1), null);

        mockMvc.perform(get(RESOLVE_PATH, supplierAId, skuId)
                .header(TENANT_HEADER, companyA.toString())
                .param("quantity", "37")
                .param("asOfDate", "2026-06-01"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cartonValid").value(true))
            .andExpect(jsonPath("$.adjustedQuantity").value(37));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void reportsCartonInvalidWithTheAdjustedQuantity() throws Exception {
        UUID skuId = seedSku(supplierAId, companyA, 10);
        seedPrice(skuId, companyA, "2.0000", LocalDate.of(2026, 1, 1), null);

        mockMvc.perform(get(RESOLVE_PATH, supplierAId, skuId)
                .header(TENANT_HEADER, companyA.toString())
                .param("quantity", "22")
                .param("asOfDate", "2026-06-01"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cartonValid").value(false))
            .andExpect(jsonPath("$.adjustedQuantity").value(20));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void crossTenantSupplierReturnsNotFound() throws Exception {
        UUID skuId = seedSku(supplierAId, companyA, null);
        seedPrice(skuId, companyA, "2.0000", LocalDate.of(2026, 1, 1), null);

        mockMvc.perform(get(RESOLVE_PATH, supplierAId, skuId)
                .header(TENANT_HEADER, companyB.toString())
                .param("quantity", "10")
                .param("asOfDate", "2026-06-01"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /**
     * Simulates the supersession invariant (3.5) having been violated by
     * seeding two overlapping windows directly via JDBC — the normal write
     * path (SkuService) would never allow this. Proves the defensive
     * tie-break resolves deterministically to the latest validFrom, and
     * that it's surfaced as a data-integrity log signal rather than
     * silently swallowed.
     */
    @Test
    @WithMockUser(roles = "READ_ONLY")
    void overlappingWindowsResolveToTheLatestValidFromAndLogAWarning() throws Exception {
        UUID skuId = seedSku(supplierAId, companyA, null);
        seedPrice(skuId, companyA, "1.0000", LocalDate.of(2026, 1, 1), null);
        UUID laterPriceId = seedPrice(skuId, companyA, "3.0000", LocalDate.of(2026, 2, 1), null);

        try (LogCapture logs = new LogCapture(PriceResolutionService.class)) {
            mockMvc.perform(get(RESOLVE_PATH, supplierAId, skuId)
                    .header(TENANT_HEADER, companyA.toString())
                    .param("quantity", "10")
                    .param("asOfDate", "2026-06-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skuPriceId").value(laterPriceId.toString()))
                .andExpect(jsonPath("$.unitPrice.amount").value("3.0000"));

            assertThat(logs.firstMessageContaining("Data integrity")).contains(skuId.toString());
        }
    }
}
