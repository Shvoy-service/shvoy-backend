package com.shvoy.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
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
 * <strong>The end-to-end proof (Story 6.8).</strong> One integration test walking
 * the whole spine through the real HTTP endpoints — create PO, price it, generate
 * (the payment schedule is born), log the PI (auto-confirms within tolerance),
 * log the shipment's BL + packing list and receipt the GRN, log the final
 * invoice, the three-way match passes, and Finance pays. Every leg is a real
 * request through the real controllers; only S3 is mocked (no document bytes to
 * store for real in a test).
 *
 * <p>This is the demo script written as code and the regression net for every
 * remodel still to come (statement, freight, NCR): it proves the last six
 * features compose. If this goes red, the pipeline is broken somewhere along its
 * length — which is exactly what you want a single named test to tell you.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = {"ADMIN", "PURCHASING", "FINANCE"})
class EndToEndPaymentLifecycleTest {

    private static final String TENANT = "X-Debug-Company-Id";
    private static final String USER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    S3Client s3Client;

    final UUID company = UUID.randomUUID();
    UUID supplierId;
    UUID userId;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        Mockito.when(s3Client.putObject(Mockito.any(PutObjectRequest.class), Mockito.any(software.amazon.awssdk.core.sync.RequestBody.class)))
            .thenReturn(PutObjectResponse.builder().build());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", company, "Acme", now);
        supplierId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO suppliers (id, name, status, validation_status, default_incoterms, created_at, company_id) "
                + "VALUES (?, ?, 'ACTIVE', 'VALIDATED', 'FOB', ?, ?)",
            supplierId, "Supplier A", now, company);
        userId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userId, "fin@acme.example", now, company);
    }

    @AfterEach
    void cleanUp() {
        // This test deliberately exercises the widest slice of the schema in one flow (six features' tables), so
        // rather than hand-maintain a full FK-topological delete order, drop referential integrity for the wipe.
        // H2-only; scoped to this single company's rows.
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            for (String table : new String[] {
                "discrepancy_case_audit_events", "discrepancy_cases", "credit_ledger_audit_events", "credit_ledger_entries",
                "invoice_match_results", "invoices",
                "reconciliation_audit_events", "approval_actions", "reconciliation_lines", "reconciliations",
                "proforma_invoice_lines", "proforma_invoices",
                "payment_audit_events", "payment_grn_projection_lines", "payments",
                "shipment_goods_receipt_lines", "shipment_packing_list_lines", "shipment_document_audit_events",
                "shipment_consignments", "shipments",
                "purchase_order_audit_events", "purchase_order_lines", "po_number_counters", "purchase_orders",
                "sku_prices", "skus", "suppliers", "users"}) {
                jdbcTemplate.update("DELETE FROM " + table + " WHERE company_id = ?", company);
            }
            jdbcTemplate.update("DELETE FROM companies WHERE id = ?", company);
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }

    @Test
    void theWholeSpineComposes_poToPiToGrnToMatchToPay() throws Exception {
        // 1. PO created, priced, and generated — the payment schedule is born (a single BALANCE, no terms → no split).
        UUID sku = seedSku();
        seedPrice(sku, "2.0000");
        UUID po = createPo();
        addLine(po, sku, 10);
        setEtd(po, LocalDate.now().plusDays(14));
        generate(po);

        UUID balance = balanceOf(po);
        assertThat(statusOf(balance)).isEqualTo("PENDING"); // no invoice/GRN yet — awaiting, honestly

        // 2. The confirmed PI (logged at PO prices/quantities → auto-confirms within tolerance).
        logPi(po, sku, "2.0000", 10);

        // 3. The shipment: BL + packing list logged, then the consignment receipted (the GRN's quantities are the leg).
        logBillOfLading(po);
        logPackingList(po, sku, 10);
        receiptGrn(po);

        // 4. The supplier's final invoice — the fourth leg, claiming the balance.
        logBalanceInvoice(po, "20.00");

        // The match has now run on every input change and passed: PO = PI = GRN vs invoice.
        assertThat(statusOf(balance)).isEqualTo("READY_TO_PAY");

        // 5. Finance pays — the human decision at the end of the pipeline.
        mockMvc.perform(post("/api/payments/{id}/pay", balance)
                .header(TENANT, company.toString()).header(USER, userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentReference\":\"BACS-7781\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAID"))
            .andExpect(jsonPath("$.paymentReference").value("BACS-7781"));

        assertThat(statusOf(balance)).isEqualTo("PAID");
        // The full audit spine exists for this one payment: born, matched, paid.
        assertThat(auditCount(balance, "MATCH_PASSED")).isEqualTo(1);
        assertThat(auditCount(balance, "PAID")).isEqualTo(1);

        // And the running position reflects the settlement immediately (derived, not stored).
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/purchase-orders/{po}/running-position", po).header(TENANT, company.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pctPaid").value(100.0));
    }

    // --- the spine, driven through real endpoints ---

    private UUID createPo() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/purchase-orders")
                .header(TENANT, company.toString()).header(USER, userId.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"supplierId\":\"" + supplierId + "\"}"))
            .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.id"));
    }

    private void addLine(UUID po, UUID sku, int qty) throws Exception {
        mockMvc.perform(post("/api/purchase-orders/{id}/lines", po)
                .header(TENANT, company.toString()).header(USER, userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuId\":\"" + sku + "\",\"quantity\":" + qty + "}"))
            .andExpect(status().isCreated());
    }

    private void setEtd(UUID po, LocalDate etd) throws Exception {
        mockMvc.perform(put("/api/purchase-orders/{id}/etd", po)
                .header(TENANT, company.toString()).header(USER, userId.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"requestedEtd\":\"" + etd + "\"}"))
            .andExpect(status().isOk());
    }

    private void generate(UUID po) throws Exception {
        mockMvc.perform(post("/api/purchase-orders/{id}/generate", po)
                .header(TENANT, company.toString()).header(USER, userId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("GENERATED"));
    }

    private void logPi(UUID po, UUID sku, String price, int qty) throws Exception {
        mockMvc.perform(post("/api/purchase-orders/{poId}/proforma-invoices", po)
                .header(TENANT, company.toString()).header(USER, userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"piReference\":\"PI-1\",\"currency\":\"USD\",\"lines\":[{\"skuId\":\"" + sku
                    + "\",\"confirmedUnitPriceAmount\":" + price + ",\"confirmedQuantity\":" + qty + "}]}"))
            .andExpect(status().isCreated());
    }

    private void logBillOfLading(UUID po) throws Exception {
        mockMvc.perform(multipart("/api/purchase-orders/{po}/shipment/bill-of-lading", po)
                .file(file("bl.pdf")).param("blReference", "BL-1").param("blDate", LocalDate.now().toString())
                .header(TENANT, company.toString()).header(USER, userId.toString()))
            .andExpect(status().isCreated());
    }

    private void logPackingList(UUID po, UUID sku, int qty) throws Exception {
        String lines = "[{\"skuId\":\"" + sku + "\",\"quantity\":" + qty + "}]";
        mockMvc.perform(multipart("/api/purchase-orders/{po}/shipment/packing-list", po)
                .file(file("pl.pdf")).param("reference", "PL-1").param("date", LocalDate.now().toString())
                .param("lines", lines)
                .header(TENANT, company.toString()).header(USER, userId.toString()))
            .andExpect(status().isCreated());
    }

    private void receiptGrn(UUID po) throws Exception {
        mockMvc.perform(post("/api/purchase-orders/{po}/shipment/provisional-grn", po)
                .header(TENANT, company.toString()).header(USER, userId.toString()))
            .andExpect(status().isCreated());
    }

    private void logBalanceInvoice(UUID po, String amount) throws Exception {
        mockMvc.perform(post("/api/purchase-orders/{poId}/invoices", po)
                .header(TENANT, company.toString()).header(USER, userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"invoiceReference\":\"INV-1\",\"amount\":" + amount + ",\"currency\":\"USD\","
                    + "\"invoiceDate\":\"" + LocalDate.now() + "\",\"coversType\":\"BALANCE\"}"))
            .andExpect(status().isCreated());
    }

    // --- seeding & helpers ---

    private UUID seedSku() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, description, status, created_at, company_id) "
                + "VALUES (?, ?, ?, 'Widget', 'ACTIVE', ?, ?)",
            id, supplierId, "SKU-" + id, Timestamp.from(Instant.now()), company);
        return id;
    }

    private void seedPrice(UUID sku, String amount) {
        jdbcTemplate.update(
            "INSERT INTO sku_prices (id, sku_id, unit_price_amount, currency, valid_from, valid_to, created_at, company_id) "
                + "VALUES (?, ?, ?, 'USD', ?, NULL, ?, ?)",
            UUID.randomUUID(), sku, new BigDecimal(amount), Date.valueOf(LocalDate.now().minusDays(1)),
            Timestamp.from(Instant.now()), company);
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, "application/pdf", "bytes".getBytes(StandardCharsets.UTF_8));
    }

    private UUID balanceOf(UUID po) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM payments WHERE purchase_order_id = ? AND type = 'BALANCE'", UUID.class, po);
    }

    private String statusOf(UUID paymentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    private int auditCount(UUID paymentId, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_audit_events WHERE payment_id = ? AND event_type = ?",
            Integer.class, paymentId, eventType);
    }
}
