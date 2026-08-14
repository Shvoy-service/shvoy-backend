package com.shvoy.reconciliation.controller;

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
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/**
 * Story 5.3 — the variance comparison, end to end: logging a PI (5.2's
 * endpoint) triggers a reconciliation, which this reads back. Same seeding
 * conventions as ProformaInvoiceControllerTest (no class-level @Transactional,
 * JDBC seeding, debug headers), plus a seeded GENERATED PO with snapshotted
 * lines and a covering SkuPrice so the price-file leg resolves.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReconciliationControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";
    private static final String USER_HEADER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID supplierAId;
    UUID userAId;

    @BeforeEach
    void seedBaseData() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);

        supplierAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierAId, "Supplier A", now, companyA);

        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "admin-a@example.com", now, companyA);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM reconciliation_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM reconciliations WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM proforma_invoice_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM proforma_invoices WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM sku_prices WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private UUID seedSku(UUID companyId, UUID supplierId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, status, created_at, company_id) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
            id, supplierId, "SKU-" + id, Timestamp.from(Instant.now()), companyId);
        return id;
    }

    private void seedPrice(UUID companyId, UUID skuId, String amount) {
        jdbcTemplate.update(
            "INSERT INTO sku_prices (id, sku_id, unit_price_amount, currency, valid_from, valid_to, created_at, company_id) "
                + "VALUES (?, ?, ?, 'USD', ?, ?, ?, ?)",
            UUID.randomUUID(), skuId, new BigDecimal(amount),
            Date.valueOf(LocalDate.now().minusDays(30)), null, Timestamp.from(Instant.now()), companyId);
    }

    private UUID seedGeneratedPo(UUID companyId, UUID supplierId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO purchase_orders "
                + "(id, supplier_id, po_number, status, created_by, currency, generated_by, generated_at, created_at, company_id) "
                + "VALUES (?, ?, ?, 'GENERATED', ?, 'USD', ?, ?, ?, ?)",
            id, supplierId, "PO-" + id, userAId, userAId, now, now, companyId);
        return id;
    }

    private void seedPoLine(UUID companyId, UUID poId, UUID skuId, String unitPrice, int quantity, int lineNumber) {
        jdbcTemplate.update(
            "INSERT INTO purchase_order_lines "
                + "(id, purchase_order_id, sku_id, line_number, quantity, unit_price_amount, currency, "
                + "price_found, priced_as_of_date, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'USD', true, ?, ?, ?)",
            UUID.randomUUID(), poId, skuId, lineNumber, quantity, new BigDecimal(unitPrice),
            Date.valueOf(LocalDate.now()), Timestamp.from(Instant.now()), companyId);
    }

    private String piLine(UUID skuId, String unitPrice, int quantity) {
        return "{\"skuId\":\"" + skuId + "\",\"confirmedUnitPriceAmount\":" + unitPrice
            + ",\"confirmedQuantity\":" + quantity + "}";
    }

    private UUID logPi(UUID poId, String currency, String... lines) throws Exception {
        String body = "{\"piReference\":\"SUP-REF\",\"currency\":\"" + currency + "\",\"lines\":["
            + String.join(",", lines) + "]}";
        MvcResult result = mockMvc.perform(post("/api/purchase-orders/{poId}/proforma-invoices", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    // --- matched lines & variance ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void cleanMatchingLineHasZeroVarianceAndResolvesPriceFileLeg() throws Exception {
        UUID skuId = seedSku(companyA, supplierAId);
        seedPrice(companyA, skuId, "2.0000");
        UUID poId = seedGeneratedPo(companyA, supplierAId);
        seedPoLine(companyA, poId, skuId, "2.0000", 10, 1);

        UUID piId = logPi(poId, "USD", piLine(skuId, "2.0000", 10));

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.varianceBasis").value("UNIT_PRICE"))
            .andExpect(jsonPath("$.currencyMismatch").value(false))
            .andExpect(jsonPath("$.priceFileAsOfDate").value(LocalDate.now().toString()))
            .andExpect(jsonPath("$.lines.length()").value(1))
            .andExpect(jsonPath("$.lines[0].findingType").value("MATCHED"))
            .andExpect(jsonPath("$.lines[0].poUnitPrice.amount").value("2.0000"))
            .andExpect(jsonPath("$.lines[0].piUnitPrice.amount").value("2.0000"))
            .andExpect(jsonPath("$.lines[0].priceFilePriceFound").value(true))
            .andExpect(jsonPath("$.lines[0].priceFileUnitPrice.amount").value("2.0000"))
            .andExpect(jsonPath("$.lines[0].unitPriceVariancePct").value(0.00))
            .andExpect(jsonPath("$.lines[0].unitPriceVarianceDirection").value("NONE"))
            .andExpect(jsonPath("$.lines[0].quantityVarianceAbs").value(0));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void priceIncreaseIsPositiveVarianceWithIncreaseDirection() throws Exception {
        UUID skuId = seedSku(companyA, supplierAId);
        seedPrice(companyA, skuId, "2.0000");
        UUID poId = seedGeneratedPo(companyA, supplierAId);
        seedPoLine(companyA, poId, skuId, "2.0000", 10, 1);

        UUID piId = logPi(poId, "USD", piLine(skuId, "2.2000", 10));

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines[0].unitPriceVariancePct").value(10.00))
            .andExpect(jsonPath("$.lines[0].unitPriceVarianceDirection").value("INCREASE"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void priceDecreaseIsNegativeVarianceWithDecreaseDirection() throws Exception {
        UUID skuId = seedSku(companyA, supplierAId);
        seedPrice(companyA, skuId, "2.0000");
        UUID poId = seedGeneratedPo(companyA, supplierAId);
        seedPoLine(companyA, poId, skuId, "2.0000", 10, 1);

        UUID piId = logPi(poId, "USD", piLine(skuId, "1.8000", 10));

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines[0].unitPriceVariancePct").value(-10.00))
            .andExpect(jsonPath("$.lines[0].unitPriceVarianceDirection").value("DECREASE"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void quantityDifferenceIsComputedSeparatelyFromPriceVariance() throws Exception {
        UUID skuId = seedSku(companyA, supplierAId);
        seedPrice(companyA, skuId, "2.0000");
        UUID poId = seedGeneratedPo(companyA, supplierAId);
        seedPoLine(companyA, poId, skuId, "2.0000", 10, 1);

        UUID piId = logPi(poId, "USD", piLine(skuId, "2.0000", 12));

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines[0].unitPriceVariancePct").value(0.00))
            .andExpect(jsonPath("$.lines[0].quantityVarianceAbs").value(2))
            .andExpect(jsonPath("$.lines[0].quantityVariancePct").value(20.00));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void smallVarianceWellWithinAnyToleranceIsStillStored() throws Exception {
        UUID skuId = seedSku(companyA, supplierAId);
        seedPrice(companyA, skuId, "2.0000");
        UUID poId = seedGeneratedPo(companyA, supplierAId);
        seedPoLine(companyA, poId, skuId, "2.0000", 10, 1);

        // 2.0000 -> 2.0100 is +0.5%, well inside any plausible tolerance — still recorded.
        UUID piId = logPi(poId, "USD", piLine(skuId, "2.0100", 10));

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines[0].unitPriceVariancePct").value(0.50));
    }

    // --- structural findings ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void piLineWithNoCorrespondingPoLineIsFlaggedUnmatched() throws Exception {
        UUID poSku = seedSku(companyA, supplierAId);
        seedPrice(companyA, poSku, "2.0000");
        UUID extraSku = seedSku(companyA, supplierAId);
        UUID poId = seedGeneratedPo(companyA, supplierAId);
        seedPoLine(companyA, poId, poSku, "2.0000", 10, 1);

        // PI confirms the PO's SKU plus an extra SKU that isn't on the PO.
        UUID piId = logPi(poId, "USD", piLine(poSku, "2.0000", 10), piLine(extraSku, "5.0000", 3));

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines.length()").value(2))
            .andExpect(jsonPath("$.lines[?(@.skuId == '" + extraSku + "')].findingType").value("UNMATCHED_PI_LINE"))
            .andExpect(jsonPath("$.lines[?(@.skuId == '" + extraSku + "')].piQuantity").value(3))
            .andExpect(jsonPath("$.lines[?(@.skuId == '" + poSku + "')].findingType").value("MATCHED"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void poLineWithNoCorrespondingPiLineIsFlaggedUnmatched() throws Exception {
        UUID confirmedSku = seedSku(companyA, supplierAId);
        seedPrice(companyA, confirmedSku, "2.0000");
        UUID omittedSku = seedSku(companyA, supplierAId);
        seedPrice(companyA, omittedSku, "4.0000");
        UUID poId = seedGeneratedPo(companyA, supplierAId);
        seedPoLine(companyA, poId, confirmedSku, "2.0000", 10, 1);
        seedPoLine(companyA, poId, omittedSku, "4.0000", 5, 2);

        // PI confirms only the first SKU; the second PO line is omitted.
        UUID piId = logPi(poId, "USD", piLine(confirmedSku, "2.0000", 10));

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines.length()").value(2))
            .andExpect(jsonPath("$.lines[?(@.skuId == '" + omittedSku + "')].findingType").value("UNMATCHED_PO_LINE"))
            .andExpect(jsonPath("$.lines[?(@.skuId == '" + omittedSku + "')].poQuantity").value(5));
    }

    // --- currency mismatch: flagged, no conversion ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void currencyMismatchIsFlaggedAndNoUnitPriceVarianceIsComputed() throws Exception {
        UUID skuId = seedSku(companyA, supplierAId);
        seedPrice(companyA, skuId, "2.0000");
        UUID poId = seedGeneratedPo(companyA, supplierAId);
        seedPoLine(companyA, poId, skuId, "2.0000", 10, 1);

        // Supplier confirms in GBP against a USD PO — a cross-currency exception, no FX conversion.
        UUID piId = logPi(poId, "GBP", piLine(skuId, "2.0000", 12));

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currencyMismatch").value(true))
            .andExpect(jsonPath("$.poCurrency").value("USD"))
            .andExpect(jsonPath("$.piCurrency").value("GBP"))
            .andExpect(jsonPath("$.lines[0].findingType").value("MATCHED"))
            .andExpect(jsonPath("$.lines[0].unitPriceVariancePct").doesNotExist())
            .andExpect(jsonPath("$.lines[0].quantityVarianceAbs").value(2));
    }

    // --- reads & tenancy ---

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void readsAreOpenToAnyAuthenticatedRole() throws Exception {
        UUID skuId = seedSku(companyA, supplierAId);
        seedPrice(companyA, skuId, "2.0000");
        UUID poId = seedGeneratedPo(companyA, supplierAId);
        // Seed a PI + its reconciliation directly (the read path, not the trigger, is what these tests exercise).
        UUID piId = seedReconciledPi(poId, skuId);

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.proformaInvoiceId").value(piId.toString()));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void reconciliationForAnotherCompanyReturnsNotFound() throws Exception {
        UUID skuId = seedSku(companyA, supplierAId);
        seedPrice(companyA, skuId, "2.0000");
        UUID poId = seedGeneratedPo(companyA, supplierAId);
        UUID piId = seedReconciledPi(poId, skuId);

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation", piId)
                .header(TENANT_HEADER, companyB.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /** Seeds a logged PI plus a recorded reconciliation for it directly, so a read/tenancy test needn't drive the trigger. */
    private UUID seedReconciledPi(UUID poId, UUID skuId) {
        UUID piId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO proforma_invoices "
                + "(id, purchase_order_id, pi_reference, currency, status, active, logged_by, created_at, company_id) "
                + "VALUES (?, ?, 'SUP-REF', 'USD', 'LOGGED', true, ?, ?, ?)",
            piId, poId, userAId, now, companyA);
        jdbcTemplate.update(
            "INSERT INTO reconciliations "
                + "(id, proforma_invoice_id, purchase_order_id, variance_basis, price_file_as_of_date, "
                + "po_currency, pi_currency, currency_mismatch, created_at, company_id) "
                + "VALUES (?, ?, ?, 'UNIT_PRICE', ?, 'USD', 'USD', false, ?, ?)",
            UUID.randomUUID(), piId, poId, Date.valueOf(LocalDate.now()), now, companyA);
        return piId;
    }
}
