package com.shvoy.suppliers.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
 * Same conventions as SkuControllerTest/SupplierControllerTest: no
 * class-level @Transactional, JDBC seeding, the debug tenant header. Seeds
 * the SKU/price chain directly via JDBC rather than through SkuController,
 * since only the tier endpoints are under test here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DiscountTierControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";
    private static final String TIERS_PATH = "/api/suppliers/{supplierId}/skus/{skuId}/prices/{priceId}/tiers";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID supplierAId;
    UUID skuAId;
    UUID priceAId;

    @BeforeEach
    void seedSupplierSkuAndPrice() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);

        supplierAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierAId, "Supplier A", now, companyA);

        skuAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, status, created_at, company_id) VALUES (?, ?, 'SKU-1', 'ACTIVE', ?, ?)",
            skuAId, supplierAId, now, companyA);

        priceAId = seedPrice(skuAId, companyA, "2.0000");
    }

    private UUID seedPrice(UUID skuId, UUID companyId, String unitPriceAmount) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO sku_prices (id, sku_id, unit_price_amount, currency, valid_from, created_at, company_id) "
                + "VALUES (?, ?, ?, 'GBP', ?, ?, ?)",
            id, skuId, new BigDecimal(unitPriceAmount), java.sql.Date.valueOf(LocalDate.now()),
            Timestamp.from(Instant.now()), companyId);
        return id;
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM discount_tiers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM sku_prices WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    // --- set ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void setReturnsTiersSortedByThresholdRegardlessOfSubmissionOrder() throws Exception {
        mockMvc.perform(put(TIERS_PATH, supplierAId, skuAId, priceAId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tiers\":[{\"quantityThreshold\":500,\"unitPriceAmount\":1.0000},"
                    + "{\"quantityThreshold\":100,\"unitPriceAmount\":1.5000}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
            .andExpect(jsonPath("$[0].quantityThreshold").value(100))
            .andExpect(jsonPath("$[0].unitPrice.amount").value("1.5000"))
            .andExpect(jsonPath("$[1].quantityThreshold").value(500))
            .andExpect(jsonPath("$[1].unitPrice.amount").value("1.0000"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void setRejectsDuplicateThresholds() throws Exception {
        mockMvc.perform(put(TIERS_PATH, supplierAId, skuAId, priceAId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tiers\":[{\"quantityThreshold\":100,\"unitPriceAmount\":1.5000},"
                    + "{\"quantityThreshold\":100,\"unitPriceAmount\":1.4000}]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void setRejectsATierPricedHigherThanTheBasePrice() throws Exception {
        mockMvc.perform(put(TIERS_PATH, supplierAId, skuAId, priceAId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tiers\":[{\"quantityThreshold\":100,\"unitPriceAmount\":2.5000}]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void setRejectsAHigherTierPricedMoreThanALowerTier() throws Exception {
        mockMvc.perform(put(TIERS_PATH, supplierAId, skuAId, priceAId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tiers\":[{\"quantityThreshold\":100,\"unitPriceAmount\":1.5000},"
                    + "{\"quantityThreshold\":200,\"unitPriceAmount\":1.6000}]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void setWithNonPositiveThresholdReturnsValidationError() throws Exception {
        mockMvc.perform(put(TIERS_PATH, supplierAId, skuAId, priceAId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tiers\":[{\"quantityThreshold\":0,\"unitPriceAmount\":1.5000}]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void setTwiceReplacesThePreviousTierSetEntirely() throws Exception {
        mockMvc.perform(put(TIERS_PATH, supplierAId, skuAId, priceAId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tiers\":[{\"quantityThreshold\":100,\"unitPriceAmount\":1.5000}]}"))
            .andExpect(status().isOk());

        mockMvc.perform(put(TIERS_PATH, supplierAId, skuAId, priceAId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tiers\":[{\"quantityThreshold\":200,\"unitPriceAmount\":1.0000}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$[0].quantityThreshold").value(200));

        Long tierCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM discount_tiers WHERE sku_price_id = ?", Long.class, priceAId);
        assertThat(tierCount).isEqualTo(1L);
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void setEmptyListClearsAllTiers() throws Exception {
        mockMvc.perform(put(TIERS_PATH, supplierAId, skuAId, priceAId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tiers\":[{\"quantityThreshold\":100,\"unitPriceAmount\":1.5000}]}"))
            .andExpect(status().isOk());

        mockMvc.perform(put(TIERS_PATH, supplierAId, skuAId, priceAId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tiers\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void setForAnotherCompanysPriceReturnsNotFound() throws Exception {
        mockMvc.perform(put(TIERS_PATH, supplierAId, skuAId, priceAId)
                .header(TENANT_HEADER, companyB.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tiers\":[{\"quantityThreshold\":100,\"unitPriceAmount\":1.5000}]}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void setIsForbiddenForReadOnlyRole() throws Exception {
        mockMvc.perform(put(TIERS_PATH, supplierAId, skuAId, priceAId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tiers\":[{\"quantityThreshold\":100,\"unitPriceAmount\":1.5000}]}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // --- get ---

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void getReturnsTiersForAnyAuthenticatedRole() throws Exception {
        jdbcTemplate.update(
            "INSERT INTO discount_tiers (id, sku_price_id, quantity_threshold, unit_price_amount, created_at, company_id) "
                + "VALUES (?, ?, 100, 1.5000, ?, ?)",
            UUID.randomUUID(), priceAId, Timestamp.from(Instant.now()), companyA);

        mockMvc.perform(get(TIERS_PATH, supplierAId, skuAId, priceAId).header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].quantityThreshold").value(100))
            .andExpect(jsonPath("$[0].unitPrice.currency").value("GBP"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void getForAnotherCompanysPriceReturnsNotFound() throws Exception {
        mockMvc.perform(get(TIERS_PATH, supplierAId, skuAId, priceAId).header(TENANT_HEADER, companyB.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
