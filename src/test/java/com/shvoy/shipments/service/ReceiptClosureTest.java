package com.shvoy.shipments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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

import com.shvoy.ConflictException;
import com.shvoy.CurrentUserContext;
import com.shvoy.TenantContext;
import com.shvoy.purchaseorders.service.PurchaseOrderService;

/**
 * Receipt rollup &amp; PO closure — cumulative receipt across shipments and the
 * closure lifecycle: auto-close on per-SKU-exact completion, the net-zero
 * strictness guard, over-delivery holding closure, amendment-driven reopen, the
 * close-short escape valve (Finance-only), and closure not freezing invoices.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = {"ADMIN", "FINANCE"})
class ReceiptClosureTest {

    private static final String TENANT = "X-Debug-Company-Id";
    private static final String USER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ReceiptRollupService receiptRollupService;

    @Autowired
    PurchaseOrderService purchaseOrderService;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID userAId;
    UUID supplierAId;
    UUID skuA;
    UUID skuB;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "closure-" + userAId + "@x.com", now, companyA);
        supplierAId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierAId, "Sup", now, companyA);
        skuA = insertSku();
        skuB = insertSku();
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        CurrentUserContext.clear();
        for (UUID c : new UUID[] {companyA, companyB}) {
            jdbcTemplate.update("DELETE FROM invoice_match_results WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM invoices WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM payments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM shipment_goods_receipt_lines WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM shipment_consignments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM shipments WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_order_audit_events WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM skus WHERE company_id = ?", c);
            jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", c);
        }
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    @Test
    void twoPartialShipmentsCompleteThePoAndItAutoCloses() {
        UUID po = sentPo(companyA);
        poLine(po, skuA, 10, "2.0000", companyA);
        UUID shipment = shipment(companyA);
        grnLine(consignment(shipment, po, companyA), skuA, 6, companyA);
        grnLine(consignment(shipment, po, companyA), skuA, 4, companyA); // second shipment completes it

        reassess(po, companyA);

        assertThat(statusOf(po)).isEqualTo("CLOSED");
        assertThat(auditCount(po, "PO_CLOSED_ON_RECEIPT")).isEqualTo(1);
    }

    @Test
    void aNetZeroPerSkuMismatchDoesNotClose() {
        UUID po = sentPo(companyA);
        poLine(po, skuA, 10, "2.0000", companyA);
        poLine(po, skuB, 10, "2.0000", companyA);
        UUID shipment = shipment(companyA);
        UUID c = consignment(shipment, po, companyA);
        grnLine(c, skuA, 5, companyA); // short on A
        grnLine(c, skuB, 15, companyA); // over on B — totals net to 20==20, but per SKU it's a double discrepancy

        reassess(po, companyA);

        assertThat(statusOf(po)).isEqualTo("SENT"); // NOT closed — closure is per-SKU exact
        assertThat(overDeliveredOf(po)).isTrue();
    }

    @Test
    void overDeliveryFlagsAndHoldsClosure() {
        UUID po = sentPo(companyA);
        poLine(po, skuA, 10, "2.0000", companyA);
        UUID shipment = shipment(companyA);
        grnLine(consignment(shipment, po, companyA), skuA, 12, companyA);

        reassess(po, companyA);

        assertThat(statusOf(po)).isEqualTo("SENT"); // exceeds ordered — a discrepancy, not completion
        assertThat(overDeliveredOf(po)).isTrue();
        assertThat(auditCount(po, "OVER_DELIVERY_FLAGGED")).isEqualTo(1);
    }

    @Test
    void anAmendmentThatUnCompletesReopensTheClosedPo() {
        UUID po = sentPo(companyA);
        poLine(po, skuA, 10, "2.0000", companyA);
        UUID shipment = shipment(companyA);
        UUID grn = grnLine(consignment(shipment, po, companyA), skuA, 10, companyA);
        reassess(po, companyA);
        assertThat(statusOf(po)).isEqualTo("CLOSED");

        jdbcTemplate.update("UPDATE shipment_goods_receipt_lines SET received_quantity = 8 WHERE id = ?", grn);
        reassess(po, companyA);

        assertThat(statusOf(po)).isEqualTo("SENT"); // reopened, loudly
        assertThat(auditCount(po, "PO_REOPENED_ON_AMENDMENT")).isEqualTo(1);
    }

    @Test
    void aClosedPoStillAcceptsInvoicesButBlocksNewShipmentDocuments() throws Exception {
        UUID po = sentPo(companyA);
        poLine(po, skuA, 10, "2.0000", companyA);
        UUID shipment = shipment(companyA);
        grnLine(consignment(shipment, po, companyA), skuA, 10, companyA);
        reassess(po, companyA);
        assertThat(statusOf(po)).isEqualTo("CLOSED");

        // Invoices still arrive (the balance invoice follows completion) — closure is a receipt fact, not a freeze.
        mockMvc.perform(post("/api/purchase-orders/{po}/invoices", po)
                .header(TENANT, companyA.toString()).header(USER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"invoiceReference\":\"INV-1\",\"amount\":20.00,\"currency\":\"USD\","
                    + "\"invoiceDate\":\"2026-03-01\",\"coversType\":\"AMOUNT\"}"))
            .andExpect(status().isCreated());

        // ...but no new shipment documents/consignments open against a closed PO.
        TenantContext.set(companyA);
        try {
            assertThatThrownBy(() -> purchaseOrderService.assertOwnPurchaseOrderReadyForShipment(po))
                .isInstanceOf(ConflictException.class);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void closeShortByFinanceSucceedsWithADistinctStateAndAudit() throws Exception {
        UUID po = sentPo(companyA);
        poLine(po, skuA, 10, "2.0000", companyA); // nothing received — full remainder outstanding

        mockMvc.perform(post("/api/purchase-orders/{po}/close-short", po)
                .header(TENANT, companyA.toString()).header(USER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"supplier discontinued the remainder\"}"))
            .andExpect(status().isOk());

        assertThat(statusOf(po)).isEqualTo("CLOSED_SHORT");
        assertThat(auditCount(po, "PO_CLOSED_SHORT")).isEqualTo(1);
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void closeShortByPurchasingIsForbidden() throws Exception {
        UUID po = sentPo(companyA);
        poLine(po, skuA, 10, "2.0000", companyA);

        mockMvc.perform(post("/api/purchase-orders/{po}/close-short", po)
                .header(TENANT, companyA.toString()).header(USER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"let it go\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void theRollupSurfacesPerSkuOrderedReceivedAndOutstanding() throws Exception {
        UUID po = sentPo(companyA);
        poLine(po, skuA, 10, "2.0000", companyA);
        UUID shipment = shipment(companyA);
        grnLine(consignment(shipment, po, companyA), skuA, 6, companyA);

        mockMvc.perform(get("/api/purchase-orders/{po}/receipt-rollup", po).header(TENANT, companyA.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.complete").value(false))
            .andExpect(jsonPath("$.overDelivered").value(false))
            .andExpect(jsonPath("$.receivedValue.amount").value("12.00")) // 6 × 2.00
            .andExpect(jsonPath("$.outstandingValue.amount").value("8.00")) // 4 × 2.00
            .andExpect(jsonPath("$.lines[0].orderedQuantity").value(10))
            .andExpect(jsonPath("$.lines[0].receivedQuantity").value(6));
    }

    @Test
    void closureIsTenantScoped() {
        UUID poB = sentPo(companyB);

        TenantContext.set(companyA);
        CurrentUserContext.set(userAId);
        try {
            assertThatThrownBy(() -> receiptRollupService.reassessClosure(poB)).isNotNull();
        } finally {
            TenantContext.clear();
            CurrentUserContext.clear();
        }
        assertThat(statusOf(poB)).isEqualTo("SENT"); // company B's PO untouched
    }

    // --- driving ---

    private void reassess(UUID po, UUID company) {
        TenantContext.set(company);
        CurrentUserContext.set(userAId);
        try {
            receiptRollupService.reassessClosure(po);
        } finally {
            TenantContext.clear();
            CurrentUserContext.clear();
        }
    }

    // --- seeding ---

    private UUID insertSku() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, description, status, created_at, company_id) "
                + "VALUES (?, ?, ?, 'Widget', 'ACTIVE', ?, ?)",
            id, supplierAId, "SKU-" + id, Timestamp.from(Instant.now()), companyA);
        return id;
    }

    private UUID sentPo(UUID company) {
        UUID po = UUID.randomUUID();
        UUID supplier = company.equals(companyA) ? supplierAId : insertSupplierFor(company);
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, generated_at, company_id) "
                + "VALUES (?, ?, ?, 'SENT', ?, ?, ?, ?)",
            po, supplier, "PO-" + po, userAId, now, now, company);
        return po;
    }

    private UUID insertSupplierFor(UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            id, "Sup-" + id, Timestamp.from(Instant.now()), company);
        return id;
    }

    private void poLine(UUID po, UUID sku, int qty, String price, UUID company) {
        jdbcTemplate.update(
            "INSERT INTO purchase_order_lines (id, company_id, purchase_order_id, sku_id, line_number, quantity, unit_price_amount, currency, price_found, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'USD', TRUE, ?)",
            UUID.randomUUID(), company, po, sku, lineNumber(po), qty, new BigDecimal(price), Timestamp.from(Instant.now()));
    }

    private int lineNumber(UUID po) {
        Integer max = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(line_number), 0) FROM purchase_order_lines WHERE purchase_order_id = ?",
            Integer.class, po);
        return max + 1;
    }

    private UUID shipment(UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO shipments (id, bl_reference, created_at, company_id) VALUES (?, ?, ?, ?)",
            id, "BL-" + id, Timestamp.from(Instant.now()), company);
        return id;
    }

    private UUID consignment(UUID shipment, UUID po, UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO shipment_consignments (id, shipment_id, purchase_order_id, receipt_status, detached, created_at, company_id) "
                + "VALUES (?, ?, ?, 'PROVISIONALLY_RECEIPTED', FALSE, ?, ?)",
            id, shipment, po, Timestamp.from(Instant.now()), company);
        return id;
    }

    private UUID grnLine(UUID consignment, UUID sku, int qty, UUID company) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO shipment_goods_receipt_lines (id, company_id, consignment_id, sku_id, received_quantity, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
            id, company, consignment, sku, qty, Timestamp.from(Instant.now()));
        return id;
    }

    // --- assertions ---

    private String statusOf(UUID po) {
        return jdbcTemplate.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class, po);
    }

    private boolean overDeliveredOf(UUID po) {
        return jdbcTemplate.queryForObject(
            "SELECT over_delivered FROM purchase_orders WHERE id = ?", Boolean.class, po);
    }

    private int auditCount(UUID po, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM purchase_order_audit_events WHERE purchase_order_id = ? AND event_type = ?",
            Integer.class, po, eventType);
    }
}
