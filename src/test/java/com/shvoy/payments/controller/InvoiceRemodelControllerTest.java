package com.shvoy.payments.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Invoice remodel — the {@code covers_type} coherence rules validated at entry
 * (existence/ownership only) and the derived running position. Well-formedness,
 * not amount agreement: the amounts are recorded faithfully, the match judges
 * them (6.5).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = {"ADMIN", "PURCHASING", "FINANCE"})
class InvoiceRemodelControllerTest {

    private static final String TENANT = "X-Debug-Company-Id";
    private static final String USER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    UUID userAId;
    UUID supplierAId;
    UUID skuAId;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "admin-" + userAId + "@example.com", now, companyA);
        supplierAId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierAId, "Supplier A", now, companyA);
        skuAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, description, status, created_at, company_id) "
                + "VALUES (?, ?, ?, 'Widget', 'ACTIVE', ?, ?)",
            skuAId, supplierAId, "SKU-A", now, companyA);
    }

    @AfterEach
    void cleanUp() {
        // A mismatching invoice drives the full 6.5/6.6 machinery (cases, audits, payment blocks) — clear it all in FK order.
        jdbcTemplate.update("DELETE FROM discrepancy_case_audit_events WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM discrepancy_cases WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM credit_ledger_audit_events WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM credit_ledger_entries WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM payment_audit_events WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM payment_grn_projection_lines WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM payments WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM invoice_covered_lines WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM invoice_match_results WHERE company_id = ?", companyA);
        jdbcTemplate.update("UPDATE invoices SET supersedes_invoice_id = NULL WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM invoices WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM users WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM companies WHERE id = ?", companyA);
    }

    // --- DEPOSIT / BALANCE coherence: the PO must carry the matching obligation ---

    @Test
    void aDepositInvoiceAgainstAPoWithNoDepositIsIncoherent() throws Exception {
        UUID po = pricedPo(10, "2.0000");
        insertPayment(po, "BALANCE", "20.00"); // no DEPOSIT obligation

        postInvoice(po, "{\"invoiceReference\":\"INV-D\",\"amount\":8.00,\"currency\":\"USD\","
            + "\"invoiceDate\":\"2026-03-01\",\"coversType\":\"DEPOSIT\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVOICE_COVERAGE_INCOHERENT"));
    }

    @Test
    void aDepositInvoiceAgainstADepositBalancePoIsAccepted() throws Exception {
        UUID po = pricedPo(10, "2.0000");
        insertPayment(po, "DEPOSIT", "6.00");
        insertPayment(po, "BALANCE", "14.00");

        postInvoice(po, "{\"invoiceReference\":\"INV-D\",\"amount\":6.00,\"currency\":\"USD\","
            + "\"invoiceDate\":\"2026-03-01\",\"coversType\":\"DEPOSIT\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.coversType").value("DEPOSIT"))
            .andExpect(jsonPath("$.weakestSignal").value(false));
    }

    // --- SHIPMENT coherence: the consignment must be receipted against the PO ---

    @Test
    void aShipmentInvoiceForAnUnreceiptedConsignmentIsIncoherent() throws Exception {
        UUID po = pricedPo(10, "2.0000");

        postInvoice(po, "{\"invoiceReference\":\"INV-S\",\"amount\":20.00,\"currency\":\"USD\","
            + "\"invoiceDate\":\"2026-03-01\",\"coversType\":\"SHIPMENT\",\"coversConsignmentId\":\""
            + UUID.randomUUID() + "\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVOICE_COVERAGE_INCOHERENT"));
    }

    @Test
    void aShipmentInvoiceForAReceiptedConsignmentIsAccepted() throws Exception {
        UUID po = pricedPo(10, "2.0000");
        UUID consignment = insertGrnProjection(po, skuAId, 10);

        postInvoice(po, "{\"invoiceReference\":\"INV-S\",\"amount\":20.00,\"currency\":\"USD\","
            + "\"invoiceDate\":\"2026-03-01\",\"coversType\":\"SHIPMENT\",\"coversConsignmentId\":\"" + consignment + "\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.coversConsignmentId").value(consignment.toString()));
    }

    // --- LINES coherence: every claimed SKU must belong to the PO ---

    @Test
    void aLinesInvoiceClaimingAForeignSkuIsIncoherent() throws Exception {
        UUID po = pricedPo(10, "2.0000");

        postInvoice(po, "{\"invoiceReference\":\"INV-L\",\"amount\":10.00,\"currency\":\"USD\","
            + "\"invoiceDate\":\"2026-03-01\",\"coversType\":\"LINES\",\"coveredLines\":[{\"skuId\":\""
            + UUID.randomUUID() + "\",\"quantity\":5}]}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVOICE_COVERAGE_INCOHERENT"));
    }

    @Test
    void aLinesInvoiceClaimingPoSkusIsAcceptedAndEchoed() throws Exception {
        UUID po = pricedPo(10, "2.0000");

        postInvoice(po, "{\"invoiceReference\":\"INV-L\",\"amount\":10.00,\"currency\":\"USD\","
            + "\"invoiceDate\":\"2026-03-01\",\"coversType\":\"LINES\",\"coveredLines\":[{\"skuId\":\""
            + skuAId + "\",\"quantity\":5}]}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.coversType").value("LINES"))
            .andExpect(jsonPath("$.coveredLines[0].skuId").value(skuAId.toString()))
            .andExpect(jsonPath("$.coveredLines[0].quantity").value(5));
    }

    @Test
    void anAmountInvoiceIsAlwaysAcceptedButFlaggedWeakest() throws Exception {
        UUID po = pricedPo(10, "2.0000");

        postInvoice(po, "{\"invoiceReference\":\"INV-A\",\"amount\":20.00,\"currency\":\"USD\","
            + "\"invoiceDate\":\"2026-03-01\",\"coversType\":\"AMOUNT\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.weakestSignal").value(true));
    }

    // --- the derived running position ---

    @Test
    void runningPositionDerivesInvoicedPaidReceivedAndOverInvoice() throws Exception {
        UUID po = pricedPo(10, "2.0000"); // PO value = 20.00
        insertGrnProjection(po, skuAId, 4); // received = 4 * 2.00 = 8.00 -> 40%
        UUID paid = insertPayment(po, "BALANCE", "5.00");
        jdbcTemplate.update("UPDATE payments SET status = 'PAID' WHERE id = ?", paid); // paid = 5.00 -> 25%

        postInvoice(po, "{\"invoiceReference\":\"INV-1\",\"amount\":10.00,\"currency\":\"USD\","
            + "\"invoiceDate\":\"2026-03-01\",\"coversType\":\"AMOUNT\"}").andExpect(status().isCreated());

        mockMvc.perform(get("/api/purchase-orders/{po}/running-position", po).header(TENANT, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.poValue.amount").value("20.00"))
            .andExpect(jsonPath("$.pctInvoiced").value(50.00))
            .andExpect(jsonPath("$.pctPaid").value(25.00))
            .andExpect(jsonPath("$.pctReceived").value(40.00))
            .andExpect(jsonPath("$.overInvoiced").value(false));

        // A second invoice pushes cumulative invoiced past the PO value — surfaced, not blocked.
        postInvoice(po, "{\"invoiceReference\":\"INV-2\",\"amount\":15.00,\"currency\":\"USD\","
            + "\"invoiceDate\":\"2026-03-02\",\"coversType\":\"AMOUNT\"}").andExpect(status().isCreated());

        mockMvc.perform(get("/api/purchase-orders/{po}/running-position", po).header(TENANT, companyA.toString()))
            .andExpect(jsonPath("$.pctInvoiced").value(125.00))
            .andExpect(jsonPath("$.overInvoiced").value(true));
    }

    // --- seeding ---

    private org.springframework.test.web.servlet.ResultActions postInvoice(UUID po, String body) throws Exception {
        return mockMvc.perform(post("/api/purchase-orders/{po}/invoices", po)
            .header(TENANT, companyA.toString()).header(USER, userAId.toString())
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID pricedPo(int qty, String price) {
        UUID po = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, generated_at, company_id) "
                + "VALUES (?, ?, ?, 'GENERATED', ?, ?, ?, ?)",
            po, supplierAId, "PO-" + po, userAId, now, now, companyA);
        jdbcTemplate.update(
            "INSERT INTO purchase_order_lines (id, company_id, purchase_order_id, sku_id, line_number, quantity, unit_price_amount, currency, price_found, created_at) "
                + "VALUES (?, ?, ?, ?, 1, ?, ?, 'USD', TRUE, ?)",
            UUID.randomUUID(), companyA, po, skuAId, qty, new BigDecimal(price), now);
        return po;
    }

    private UUID insertPayment(UUID po, String type, String amount) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO payments (id, company_id, purchase_order_id, type, amount_amount, currency, status, created_at) "
                + "VALUES (?, ?, ?, ?, ?, 'USD', 'PENDING', ?)",
            id, companyA, po, type, new BigDecimal(amount), Timestamp.from(Instant.now()));
        return id;
    }

    private UUID insertGrnProjection(UUID po, UUID sku, int qty) {
        UUID consignment = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO payment_grn_projection_lines (id, company_id, purchase_order_id, consignment_id, sku_id, received_quantity, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), companyA, po, consignment, sku, qty, Timestamp.from(Instant.now()));
        return consignment;
    }
}
