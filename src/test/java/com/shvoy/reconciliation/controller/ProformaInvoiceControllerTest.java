package com.shvoy.reconciliation.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/**
 * Story 5.2 — log a supplier's confirmed PI against a PO, and read it back.
 * Same conventions as PurchaseOrderControllerTest: no class-level
 * @Transactional, JDBC seeding, the debug tenant/user headers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProformaInvoiceControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";
    private static final String USER_HEADER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID supplierAId;
    UUID supplierBId;
    UUID userAId;

    @BeforeEach
    void seedCompaniesSuppliersAndUser() {
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

        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "admin-a@example.com", now, companyA);
    }

    @AfterEach
    void cleanUp() {
        // Logging a PI now triggers a reconciliation (Story 5.3), so these rows exist and must be cleared first.
        jdbcTemplate.update("DELETE FROM reconciliation_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM reconciliations WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM proforma_invoice_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM proforma_invoices WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private UUID seedPo(UUID supplierId, UUID companyId, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, supplierId, "PO-" + id, status, userAId, Timestamp.from(Instant.now()), companyId);
        return id;
    }

    private UUID seedSku(UUID supplierId, UUID companyId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, status, created_at, company_id) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
            id, supplierId, "SKU-" + id, Timestamp.from(Instant.now()), companyId);
        return id;
    }

    private String logRequestBody(String piReference, String currency, UUID skuId, String unitPrice, int quantity) {
        return "{\"piReference\":\"" + piReference + "\",\"currency\":\"" + currency + "\","
            + "\"lines\":[{\"skuId\":\"" + skuId + "\",\"confirmedUnitPriceAmount\":" + unitPrice
            + ",\"confirmedQuantity\":" + quantity + "}]}";
    }

    private UUID seedProformaInvoice(UUID poId, UUID companyId, UUID skuId, String piReference) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO proforma_invoices "
                + "(id, purchase_order_id, pi_reference, currency, status, active, logged_by, created_at, company_id) "
                + "VALUES (?, ?, ?, 'USD', 'LOGGED', true, ?, ?, ?)",
            id, poId, piReference, userAId, now, companyId);
        jdbcTemplate.update(
            "INSERT INTO proforma_invoice_lines "
                + "(id, proforma_invoice_id, sku_id, line_number, confirmed_unit_price_amount, confirmed_quantity, created_at, company_id) "
                + "VALUES (?, ?, ?, 1, 2.5000, 10, ?, ?)",
            UUID.randomUUID(), id, skuId, now, companyId);
        return id;
    }

    private MvcResult logPi(UUID poId, String body) throws Exception {
        return mockMvc.perform(post("/api/purchase-orders/{poId}/proforma-invoices", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
    }

    // --- logging ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void logAgainstGeneratedPoRecordsItAsActive() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "GENERATED");
        UUID skuId = seedSku(supplierAId, companyA);

        mockMvc.perform(post("/api/purchase-orders/{poId}/proforma-invoices", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(logRequestBody("SUP-REF-1", "USD", skuId, "2.5000", 10)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.purchaseOrderId").value(poId.toString()))
            .andExpect(jsonPath("$.piReference").value("SUP-REF-1"))
            .andExpect(jsonPath("$.currency").value("USD"))
            // Logging now triggers evaluation (Story 5.4): this PO has no lines, so the PI's line is
            // an unmatched (structural) finding, which routes rather than staying LOGGED.
            .andExpect(jsonPath("$.status").value("ROUTED_FOR_APPROVAL"))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.loggedBy").value(userAId.toString()))
            .andExpect(jsonPath("$.lines[0].skuId").value(skuId.toString()))
            .andExpect(jsonPath("$.lines[0].lineNumber").value(1))
            .andExpect(jsonPath("$.lines[0].confirmedUnitPrice.amount").value("2.5000"))
            .andExpect(jsonPath("$.lines[0].confirmedQuantity").value(10));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void logAgainstSentPoSucceeds() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "SENT");
        UUID skuId = seedSku(supplierAId, companyA);

        mockMvc.perform(post("/api/purchase-orders/{poId}/proforma-invoices", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(logRequestBody("SUP-REF-1", "USD", skuId, "2.5000", 10)))
            .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void logAgainstDraftPoIsRejected() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "DRAFT");
        UUID skuId = seedSku(supplierAId, companyA);

        mockMvc.perform(post("/api/purchase-orders/{poId}/proforma-invoices", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(logRequestBody("SUP-REF-1", "USD", skuId, "2.5000", 10)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PO_NOT_READY_FOR_PI"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void logWithPriceDifferingFromPoIsRecordedNotRejected() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "SENT");
        UUID skuId = seedSku(supplierAId, companyA);

        // The PO's own line pricing (Feature 4) is irrelevant here — this story
        // doesn't seed or compare against it; a wildly different confirmed
        // price must still be recorded, not rejected.
        mockMvc.perform(post("/api/purchase-orders/{poId}/proforma-invoices", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(logRequestBody("SUP-REF-1", "USD", skuId, "999.9999", 10)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.lines[0].confirmedUnitPrice.amount").value("999.9999"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void logWithDifferentCurrencyIsRecordedNotBlocked() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "SENT");
        UUID skuId = seedSku(supplierAId, companyA);

        mockMvc.perform(post("/api/purchase-orders/{poId}/proforma-invoices", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(logRequestBody("SUP-REF-1", "GBP", skuId, "2.5000", 10)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.currency").value("GBP"))
            .andExpect(jsonPath("$.lines[0].confirmedUnitPrice.currency").value("GBP"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void logWithUnknownSkuIsRejected() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "SENT");
        UUID unknownSkuId = UUID.randomUUID();

        mockMvc.perform(post("/api/purchase-orders/{poId}/proforma-invoices", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(logRequestBody("SUP-REF-1", "USD", unknownSkuId, "2.5000", 10)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void logWithNegativeQuantityIsRejectedAsValidationError() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "SENT");
        UUID skuId = seedSku(supplierAId, companyA);

        mockMvc.perform(post("/api/purchase-orders/{poId}/proforma-invoices", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(logRequestBody("SUP-REF-1", "USD", skuId, "2.5000", -1)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void logForAnotherCompanysPurchaseOrderReturnsNotFound() throws Exception {
        UUID poId = seedPo(supplierBId, companyB, "SENT");
        UUID skuId = seedSku(supplierBId, companyB);

        mockMvc.perform(post("/api/purchase-orders/{poId}/proforma-invoices", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(logRequestBody("SUP-REF-1", "USD", skuId, "2.5000", 10)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void logIsForbiddenForReadOnlyRole() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "SENT");
        UUID skuId = seedSku(supplierAId, companyA);

        mockMvc.perform(post("/api/purchase-orders/{poId}/proforma-invoices", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(logRequestBody("SUP-REF-1", "USD", skuId, "2.5000", 10)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // --- supersession ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void loggingASecondPiSupersedesTheFirst() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "SENT");
        UUID skuId = seedSku(supplierAId, companyA);

        MvcResult first = logPi(poId, logRequestBody("SUP-REF-1", "USD", skuId, "2.5000", 10));
        UUID firstId = UUID.fromString(JsonPath.read(first.getResponse().getContentAsString(), "$.id"));

        MvcResult second = logPi(poId, logRequestBody("SUP-REF-2", "USD", skuId, "3.0000", 10));
        UUID secondId = UUID.fromString(JsonPath.read(second.getResponse().getContentAsString(), "$.id"));

        mockMvc.perform(get("/api/proforma-invoices/{id}", firstId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/proforma-invoices/{id}", secondId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(get("/api/purchase-orders/{poId}/proforma-invoices", poId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
    }

    // --- reads (open to any authenticated role) ---

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void getForAnotherCompanysProformaInvoiceReturnsNotFound() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "SENT");
        UUID skuId = seedSku(supplierAId, companyA);
        UUID piId = seedProformaInvoice(poId, companyA, skuId, "SUP-REF-1");

        mockMvc.perform(get("/api/proforma-invoices/{id}", piId)
                .header(TENANT_HEADER, companyB.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void listForPurchaseOrderReturnsOnlyItsOwnCompanysPis() throws Exception {
        UUID poId = seedPo(supplierAId, companyA, "SENT");
        UUID otherPoId = seedPo(supplierAId, companyA, "SENT");
        UUID skuId = seedSku(supplierAId, companyA);
        seedProformaInvoice(poId, companyA, skuId, "SUP-REF-1");
        seedProformaInvoice(otherPoId, companyA, skuId, "SUP-REF-2");

        mockMvc.perform(get("/api/purchase-orders/{poId}/proforma-invoices", poId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].piReference").value("SUP-REF-1"));
    }
}
