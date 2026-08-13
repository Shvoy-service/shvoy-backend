package com.shvoy.suppliers.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Date;
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
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/**
 * Same conventions as SupplierControllerTest/PaymentTermsControllerTest: no
 * class-level @Transactional, JDBC seeding, the debug tenant header.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkuControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";

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
        jdbcTemplate.update("DELETE FROM sku_prices WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private UUID createSku(UUID supplierId, String code, String validFrom, String validTo) throws Exception {
        String validToJson = validTo == null ? "null" : "\"" + validTo + "\"";
        MvcResult result = mockMvc.perform(post("/api/suppliers/{id}/skus", supplierId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"description\":\"Widget\",\"unitPriceAmount\":1.4275,"
                    + "\"currency\":\"GBP\",\"validFrom\":\"" + validFrom + "\",\"validTo\":" + validToJson + "}"))
            .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.sku.id"));
    }

    private UUID seedSku(UUID supplierId, UUID companyId, String code) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, status, created_at, company_id) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
            id, supplierId, code, now, companyId);
        return id;
    }

    // --- create ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void createAddsSkuWithFirstPrice() throws Exception {
        mockMvc.perform(post("/api/suppliers/{id}/skus", supplierAId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"SKU-1\",\"description\":\"Widget\",\"unitPriceAmount\":1.4275,"
                    + "\"currency\":\"GBP\",\"validFrom\":\"2026-01-01\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sku.code").value("SKU-1"))
            .andExpect(jsonPath("$.sku.status").value("ACTIVE"))
            .andExpect(jsonPath("$.currentPrice.unitPrice.amount").value("1.4275"))
            .andExpect(jsonPath("$.currentPrice.unitPrice.currency").value("GBP"))
            .andExpect(jsonPath("$.currentPrice.validFrom").value("2026-01-01"))
            .andExpect(jsonPath("$.currentPrice.validTo").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void createWithBlankCodeReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/suppliers/{id}/skus", supplierAId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"\",\"unitPriceAmount\":1.4275,\"currency\":\"GBP\",\"validFrom\":\"2026-01-01\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void createWithTooManyDecimalPlacesReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/suppliers/{id}/skus", supplierAId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"SKU-1\",\"unitPriceAmount\":1.42753,\"currency\":\"GBP\","
                    + "\"validFrom\":\"2026-01-01\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void createWithMalformedCurrencyReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/suppliers/{id}/skus", supplierAId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"SKU-1\",\"unitPriceAmount\":1.4275,\"currency\":\"gb\","
                    + "\"validFrom\":\"2026-01-01\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void createWithDuplicateCodeCaseInsensitiveReturnsConflict() throws Exception {
        createSku(supplierAId, "SKU-1", "2026-01-01", null);

        mockMvc.perform(post("/api/suppliers/{id}/skus", supplierAId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"sku-1\",\"unitPriceAmount\":1.4275,\"currency\":\"GBP\","
                    + "\"validFrom\":\"2026-01-01\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void createForAnotherCompanysSupplierReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/suppliers/{id}/skus", supplierBId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"SKU-1\",\"unitPriceAmount\":1.4275,\"currency\":\"GBP\","
                    + "\"validFrom\":\"2026-01-01\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void createIsForbiddenForReadOnlyRole() throws Exception {
        mockMvc.perform(post("/api/suppliers/{id}/skus", supplierAId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"SKU-1\",\"unitPriceAmount\":1.4275,\"currency\":\"GBP\","
                    + "\"validFrom\":\"2026-01-01\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // --- addPrice / supersession ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void addPriceSupersedesOpenRowAutoClosingIt() throws Exception {
        UUID skuId = createSku(supplierAId, "SKU-1", "2026-01-01", null);

        mockMvc.perform(post("/api/suppliers/{id}/skus/{skuId}/prices", supplierAId, skuId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitPriceAmount\":2.0000,\"currency\":\"GBP\",\"validFrom\":\"2026-03-01\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.validFrom").value("2026-03-01"))
            .andExpect(jsonPath("$.validTo").doesNotExist());

        var priorRow = jdbcTemplate.queryForMap(
            "SELECT valid_to FROM sku_prices WHERE sku_id = ? AND valid_from = ?", skuId, Date.valueOf("2026-01-01"));
        assertThat(priorRow.get("valid_to")).isEqualTo(Date.valueOf("2026-02-28"));

        Long rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sku_prices WHERE sku_id = ?", Long.class, skuId);
        assertThat(rowCount).isEqualTo(2L);
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void addPriceRejectsBackdatedStart() throws Exception {
        UUID skuId = createSku(supplierAId, "SKU-1", "2026-01-01", null);

        mockMvc.perform(post("/api/suppliers/{id}/skus/{skuId}/prices", supplierAId, skuId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitPriceAmount\":2.0000,\"currency\":\"GBP\",\"validFrom\":\"2025-12-01\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AMBIGUOUS_PRICE_WINDOW"));

        Long rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sku_prices WHERE sku_id = ?", Long.class, skuId);
        assertThat(rowCount).isEqualTo(1L);
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void addPriceRejectsDuplicateStartDate() throws Exception {
        UUID skuId = createSku(supplierAId, "SKU-1", "2026-01-01", null);

        mockMvc.perform(post("/api/suppliers/{id}/skus/{skuId}/prices", supplierAId, skuId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitPriceAmount\":2.0000,\"currency\":\"GBP\",\"validFrom\":\"2026-01-01\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AMBIGUOUS_PRICE_WINDOW"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void addPriceAcceptsContiguousWindowWhenNoOpenRow() throws Exception {
        UUID skuId = createSku(supplierAId, "SKU-1", "2026-01-01", "2026-01-31");

        mockMvc.perform(post("/api/suppliers/{id}/skus/{skuId}/prices", supplierAId, skuId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitPriceAmount\":2.0000,\"currency\":\"GBP\",\"validFrom\":\"2026-02-01\"}"))
            .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void addPriceRejectsGapWhenNoOpenRow() throws Exception {
        UUID skuId = createSku(supplierAId, "SKU-1", "2026-01-01", "2026-01-31");

        mockMvc.perform(post("/api/suppliers/{id}/skus/{skuId}/prices", supplierAId, skuId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitPriceAmount\":2.0000,\"currency\":\"GBP\",\"validFrom\":\"2026-03-01\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AMBIGUOUS_PRICE_WINDOW"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void addPriceForAnotherCompanysSkuReturnsNotFound() throws Exception {
        UUID skuId = createSku(supplierAId, "SKU-1", "2026-01-01", null);

        mockMvc.perform(post("/api/suppliers/{id}/skus/{skuId}/prices", supplierAId, skuId)
                .header(TENANT_HEADER, companyB.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitPriceAmount\":2.0000,\"currency\":\"GBP\",\"validFrom\":\"2026-03-01\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // --- update ---

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateChangesMetadataButNotPrice() throws Exception {
        UUID skuId = createSku(supplierAId, "SKU-1", "2026-01-01", null);

        mockMvc.perform(put("/api/suppliers/{id}/skus/{skuId}", supplierAId, skuId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"SKU-1-RENAMED\",\"description\":\"New desc\",\"status\":\"INACTIVE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SKU-1-RENAMED"))
            .andExpect(jsonPath("$.status").value("INACTIVE"));

        String amount = jdbcTemplate.queryForObject(
            "SELECT unit_price_amount FROM sku_prices WHERE sku_id = ?", String.class, skuId);
        assertThat(new BigDecimal(amount)).isEqualByComparingTo("1.4275");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateToAnotherSkusCodeReturnsConflict() throws Exception {
        createSku(supplierAId, "SKU-1", "2026-01-01", null);
        UUID skuId2 = createSku(supplierAId, "SKU-2", "2026-01-01", null);

        mockMvc.perform(put("/api/suppliers/{id}/skus/{skuId}", supplierAId, skuId2)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"SKU-1\",\"status\":\"ACTIVE\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
    }

    @Test
    @WithMockUser(roles = "FINANCE")
    void updateIsForbiddenForFinanceRole() throws Exception {
        UUID skuId = seedSku(supplierAId, companyA, "SKU-1");

        mockMvc.perform(put("/api/suppliers/{id}/skus/{skuId}", supplierAId, skuId)
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"SKU-1\",\"status\":\"ACTIVE\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
