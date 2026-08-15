package com.shvoy.purchaseorders.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * The PO-issuance gate (PO-issuance gate story): the validated-supplier check at
 * creation and re-checked at generation, the incoterms gate + per-supplier
 * default pre-fill, and the advisory contract/compliance flags stamped at
 * generation and cleared when the loose ends land. S3 is mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class PurchaseOrderIssuanceControllerTest {

    private static final String TENANT = "X-Debug-Company-Id";
    private static final String USER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    S3Client s3Client;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID userAId;

    @BeforeEach
    void seed() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
            .thenReturn(PutObjectResponse.builder().build());
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at, default_delivery_address) VALUES (?, ?, ?, ?)",
            companyA, "Co A", now, "1 Warehouse Way, Portville");
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
            jdbcTemplate.update("DELETE FROM purchase_order_audit_events WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM payments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM po_number_counters WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM sku_prices WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM skus WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", c);
        }
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void creatingAgainstAPendingSupplierIsRejected() throws Exception {
        UUID supplier = insertSupplier(companyA, "PENDING", "FOB");
        mockMvc.perform(createPo(supplier))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SUPPLIER_NOT_VALIDATED"));
    }

    @Test
    void creatingAgainstAValidatedSupplierPrefillsIncotermsAndDeliveryAndCanBeOverridden() throws Exception {
        UUID supplier = insertSupplier(companyA, "VALIDATED", "FOB");
        MvcResult result = mockMvc.perform(createPo(supplier))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.incoterms").value("FOB"))
            .andExpect(jsonPath("$.deliveryAddress").value("1 Warehouse Way, Portville"))
            .andReturn();
        UUID poId = poId(result);

        mockMvc.perform(put("/api/purchase-orders/{id}/details", poId).header(TENANT, companyA).header(USER, userAId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"incoterms\":\"CIF\",\"deliveryAddress\":\"2 Other Rd\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.incoterms").value("CIF"))
            .andExpect(jsonPath("$.deliveryAddress").value("2 Other Rd"));
    }

    @Test
    void generationWithoutIncotermsIsBlocked() throws Exception {
        UUID supplier = insertSupplier(companyA, "VALIDATED", null); // no default → PO has no incoterms
        UUID poId = readyPo(supplier);
        mockMvc.perform(generate(poId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("incoterms")));
    }

    @Test
    void aSupplierRevertedToPendingBlocksGeneration() throws Exception {
        UUID supplier = insertSupplier(companyA, "VALIDATED", "FOB");
        UUID poId = readyPo(supplier);
        // The bank-details-change revert (supplier remodel) takes the supplier back to PENDING mid-draft.
        jdbcTemplate.update("UPDATE suppliers SET validation_status = 'PENDING' WHERE id = ?", supplier);

        mockMvc.perform(generate(poId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SUPPLIER_NOT_VALIDATED"));
    }

    @Test
    void generatingWithMissingContractAndCertsSucceedsFlaggedThenTheFlagsClear() throws Exception {
        // Validated (so it can order) but compliance not confirmed, and no contract reference.
        UUID supplier = insertSupplier(companyA, "VALIDATED", "FOB");
        UUID poId = readyPo(supplier);

        mockMvc.perform(generate(poId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contractPending").value(true))
            .andExpect(jsonPath("$.compliancePending").value(true));
        assertThat(auditCount(poId, "ADVISORY_FLAGS_STAMPED")).isEqualTo(1);

        // The contract lands and the supplier's compliance is confirmed → both flags clear, audited.
        jdbcTemplate.update("UPDATE suppliers SET compliance_status = 'CONFIRMED' WHERE id = ?", supplier);
        mockMvc.perform(post("/api/purchase-orders/{id}/advisory-flags/refresh", poId).header(TENANT, companyA).header(USER, userAId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"contractReference\":\"MSA-2026-07\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contractPending").value(false))
            .andExpect(jsonPath("$.compliancePending").value(false));
        assertThat(auditCount(poId, "CONTRACT_PENDING_CLEARED")).isEqualTo(1);
        assertThat(auditCount(poId, "COMPLIANCE_PENDING_CLEARED")).isEqualTo(1);
    }

    @Test
    void cannotCreateAgainstAnotherCompanysSupplier() throws Exception {
        UUID supplierB = insertSupplier(companyB, "VALIDATED", "FOB");
        mockMvc.perform(createPo(supplierB))
            .andExpect(status().isNotFound());
    }

    // --- helpers ---

    /** Create a PO, add a priced line, set an ETD — everything but incoterms/generation. */
    private UUID readyPo(UUID supplier) throws Exception {
        MvcResult result = mockMvc.perform(createPo(supplier)).andExpect(status().isCreated()).andReturn();
        UUID poId = poId(result);
        UUID sku = insertSku(supplier, companyA);
        insertPrice(sku, "2.0000");
        mockMvc.perform(post("/api/purchase-orders/{id}/lines", poId).header(TENANT, companyA).header(USER, userAId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"skuId\":\"" + sku + "\",\"quantity\":10}"))
            .andExpect(status().isCreated());
        mockMvc.perform(put("/api/purchase-orders/{id}/etd", poId).header(TENANT, companyA).header(USER, userAId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestedEtd\":\"" + LocalDate.now().plusDays(14) + "\"}"))
            .andExpect(status().isOk());
        return poId;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder createPo(UUID supplier) {
        return post("/api/purchase-orders").header(TENANT, companyA).header(USER, userAId)
            .contentType(MediaType.APPLICATION_JSON).content("{\"supplierId\":\"" + supplier + "\"}");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder generate(UUID poId) {
        return post("/api/purchase-orders/{id}/generate", poId).header(TENANT, companyA).header(USER, userAId);
    }

    private UUID poId(MvcResult result) throws Exception {
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private UUID insertSupplier(UUID company, String validation, String defaultIncoterms) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, validation_status, default_incoterms, created_at, company_id) "
                + "VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?)",
            id, "Supplier-" + id, validation, defaultIncoterms, Timestamp.from(Instant.now()), company);
        return id;
    }

    private UUID insertSku(UUID supplier, UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, description, status, created_at, company_id) "
                + "VALUES (?, ?, ?, 'Widget', 'ACTIVE', ?, ?)",
            id, supplier, "SKU-" + id, Timestamp.from(Instant.now()), company);
        return id;
    }

    private void insertPrice(UUID skuId, String amount) {
        jdbcTemplate.update(
            "INSERT INTO sku_prices (id, sku_id, unit_price_amount, currency, valid_from, valid_to, created_at, company_id) "
                + "VALUES (?, ?, ?, 'USD', ?, NULL, ?, ?)",
            UUID.randomUUID(), skuId, new BigDecimal(amount), Date.valueOf(LocalDate.now().minusDays(1)),
            Timestamp.from(Instant.now()), companyA);
    }

    private int auditCount(UUID poId, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM purchase_order_audit_events WHERE purchase_order_id = ? AND event_type = ?",
            Integer.class, poId, eventType);
    }
}
