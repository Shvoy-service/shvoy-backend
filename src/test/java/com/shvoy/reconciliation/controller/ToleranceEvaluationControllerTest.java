package com.shvoy.reconciliation.controller;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/**
 * Story 5.4 — tolerance evaluation & auto-confirm, end to end: logging a PI
 * triggers the comparison (5.3) then the evaluation (5.4), which this reads
 * back. The mandatory boundary trio (just-below auto-confirms, exactly-at
 * routes, just-above routes) is here, plus structural-finding and
 * currency-mismatch routing, and the tolerance config endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ToleranceEvaluationControllerTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";
    private static final String USER_HEADER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    UUID supplierAId;
    UUID userAId;

    @BeforeEach
    void seedBaseData() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
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
        jdbcTemplate.update("DELETE FROM reconciliation_audit_events WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM reconciliation_lines WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM reconciliations WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM tolerance_settings WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM proforma_invoice_lines WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM proforma_invoices WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM sku_prices WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM users WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM companies WHERE id = ?", companyA);
    }

    private UUID seedSku() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, status, created_at, company_id) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
            id, supplierAId, "SKU-" + id, Timestamp.from(Instant.now()), companyA);
        return id;
    }

    private void seedPrice(UUID skuId, String amount) {
        jdbcTemplate.update(
            "INSERT INTO sku_prices (id, sku_id, unit_price_amount, currency, valid_from, valid_to, created_at, company_id) "
                + "VALUES (?, ?, ?, 'USD', ?, ?, ?, ?)",
            UUID.randomUUID(), skuId, new BigDecimal(amount),
            Date.valueOf(LocalDate.now().minusDays(30)), null, Timestamp.from(Instant.now()), companyA);
    }

    private UUID seedGeneratedPo() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO purchase_orders "
                + "(id, supplier_id, po_number, status, created_by, currency, generated_by, generated_at, created_at, company_id) "
                + "VALUES (?, ?, ?, 'GENERATED', ?, 'USD', ?, ?, ?, ?)",
            id, supplierAId, "PO-" + id, userAId, userAId, now, now, companyA);
        return id;
    }

    private void seedPoLine(UUID poId, UUID skuId, String unitPrice, int quantity, int lineNumber) {
        jdbcTemplate.update(
            "INSERT INTO purchase_order_lines "
                + "(id, purchase_order_id, sku_id, line_number, quantity, unit_price_amount, currency, "
                + "price_found, priced_as_of_date, created_at, company_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'USD', true, ?, ?, ?)",
            UUID.randomUUID(), poId, skuId, lineNumber, quantity, new BigDecimal(unitPrice),
            Date.valueOf(LocalDate.now()), Timestamp.from(Instant.now()), companyA);
    }

    private void seedToleranceSetting(String tolerance) {
        jdbcTemplate.update(
            "INSERT INTO tolerance_settings (id, tolerance_percentage, created_at, company_id) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(), new BigDecimal(tolerance), Timestamp.from(Instant.now()), companyA);
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

    /** Logs a single-line PI against a fresh 2.0000-priced PO and returns the PI id. */
    private UUID logSingleLinePi(String piUnitPrice) throws Exception {
        UUID skuId = seedSku();
        seedPrice(skuId, "2.0000");
        UUID poId = seedGeneratedPo();
        seedPoLine(poId, skuId, "2.0000", 10, 1);
        return logPi(poId, "USD", piLine(skuId, piUnitPrice, 10));
    }

    // --- the mandatory boundary trio (default tolerance 2.00%) ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void varianceJustBelowToleranceAutoConfirms() throws Exception {
        // 2.0000 -> 2.0398 is +1.99%, strictly below 2% -> auto-confirmed.
        UUID piId = logSingleLinePi("2.0398");

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines[0].unitPriceVariancePct").value(1.99))
            .andExpect(jsonPath("$.toleranceApplied").value(2.00))
            .andExpect(jsonPath("$.outcome").value("AUTO_CONFIRMED"))
            .andExpect(jsonPath("$.routingReasons").isEmpty());

        mockMvc.perform(get("/api/proforma-invoices/{piId}", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(jsonPath("$.status").value("AUTO_CONFIRMED"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void varianceExactlyAtToleranceRoutes_theExclusiveBoundary() throws Exception {
        // 2.0000 -> 2.0400 is exactly +2.00%. Against a 2% tolerance this is OUTSIDE (exclusive
        // boundary) and routes. THIS is the test that pins the rule three rounds of questions settled.
        UUID piId = logSingleLinePi("2.0400");

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines[0].unitPriceVariancePct").value(2.00))
            .andExpect(jsonPath("$.outcome").value("ROUTED_FOR_APPROVAL"))
            .andExpect(jsonPath("$.routingReasons", org.hamcrest.Matchers.contains("VARIANCE_OUTSIDE_TOLERANCE")));

        mockMvc.perform(get("/api/proforma-invoices/{piId}", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(jsonPath("$.status").value("ROUTED_FOR_APPROVAL"));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void varianceJustAboveToleranceRoutes() throws Exception {
        // 2.0000 -> 2.0402 is +2.01% -> routes.
        UUID piId = logSingleLinePi("2.0402");

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lines[0].unitPriceVariancePct").value(2.01))
            .andExpect(jsonPath("$.outcome").value("ROUTED_FOR_APPROVAL"))
            .andExpect(jsonPath("$.routingReasons", org.hamcrest.Matchers.contains("VARIANCE_OUTSIDE_TOLERANCE")));
    }

    // --- clean PI, structural finding, currency mismatch ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void cleanPiAutoConfirmsWithVarianceStored() throws Exception {
        UUID piId = logSingleLinePi("2.0000");

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("AUTO_CONFIRMED"))
            // The variance is still persisted on the auto-confirmed match — the drift-trend requirement.
            .andExpect(jsonPath("$.lines[0].unitPriceVariancePct").value(0.00));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void structuralFindingRoutesEvenWhenMatchedLinesAreWithinTolerance() throws Exception {
        UUID poSku = seedSku();
        seedPrice(poSku, "2.0000");
        UUID extraSku = seedSku();
        UUID poId = seedGeneratedPo();
        seedPoLine(poId, poSku, "2.0000", 10, 1);

        // The matched line is a perfect match (within tolerance), but the PI adds an extra SKU.
        UUID piId = logPi(poId, "USD", piLine(poSku, "2.0000", 10), piLine(extraSku, "5.0000", 3));

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("ROUTED_FOR_APPROVAL"))
            .andExpect(jsonPath("$.routingReasons", org.hamcrest.Matchers.contains("STRUCTURAL_FINDING")));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void currencyMismatchRoutesWithNoConversion() throws Exception {
        UUID skuId = seedSku();
        seedPrice(skuId, "2.0000");
        UUID poId = seedGeneratedPo();
        seedPoLine(poId, skuId, "2.0000", 10, 1);

        UUID piId = logPi(poId, "GBP", piLine(skuId, "2.0000", 10));

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currencyMismatch").value(true))
            .andExpect(jsonPath("$.outcome").value("ROUTED_FOR_APPROVAL"))
            .andExpect(jsonPath("$.routingReasons", org.hamcrest.Matchers.contains("CURRENCY_MISMATCH")))
            // No FX conversion — the cross-currency unit-price variance is left uncomputed.
            .andExpect(jsonPath("$.lines[0].unitPriceVariancePct").doesNotExist());
    }

    // --- configurable per-account tolerance ---

    @Test
    @WithMockUser(roles = "PURCHASING")
    void aConfiguredToleranceChangesTheOutcome() throws Exception {
        seedToleranceSetting("5.00");
        // +2.00% would route under the 2% default, but auto-confirms under a configured 5%.
        UUID piId = logSingleLinePi("2.0400");

        mockMvc.perform(get("/api/proforma-invoices/{piId}/reconciliation", piId)
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.toleranceApplied").value(5.00))
            .andExpect(jsonPath("$.outcome").value("AUTO_CONFIRMED"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void toleranceGetReturnsTheDefaultWhenUnset() throws Exception {
        mockMvc.perform(get("/api/reconciliation/tolerance")
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tolerancePercentage").value(2.00))
            .andExpect(jsonPath("$.usingDefault").value(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanConfigureTolerance() throws Exception {
        mockMvc.perform(put("/api/reconciliation/tolerance")
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tolerancePercentage\":3.50}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tolerancePercentage").value(3.50))
            .andExpect(jsonPath("$.usingDefault").value(false));

        mockMvc.perform(get("/api/reconciliation/tolerance")
                .header(TENANT_HEADER, companyA.toString()))
            .andExpect(jsonPath("$.tolerancePercentage").value(3.50))
            .andExpect(jsonPath("$.usingDefault").value(false));
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void configuringToleranceIsForbiddenForNonAdmin() throws Exception {
        mockMvc.perform(put("/api/reconciliation/tolerance")
                .header(TENANT_HEADER, companyA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tolerancePercentage\":3.50}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
