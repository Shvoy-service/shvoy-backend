package com.shvoy.shipments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Story 7.2's headline acceptance criterion, end to end and across three
 * modules: logging a BL publishes the {@code BL} anchor event, {@code
 * PaymentDueDateService} (6.2, in payments) reacts, and a BL-anchored balance's
 * due date is filled in — the first time a real shipment date drives payment
 * timing. This is exactly the seam that unit tests on each side can pass while
 * the wiring between them is broken, so it's driven through the real endpoints.
 * S3 is mocked, same as PurchaseOrderGenerationControllerTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "PURCHASING")
class ShipmentAnchorSeamTest {

    private static final String TENANT_HEADER = "X-Debug-Company-Id";
    private static final String USER_HEADER = "X-Debug-User-Id";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    S3Client s3Client;

    final UUID companyA = UUID.randomUUID();
    UUID supplierAId;
    UUID userAId;

    @BeforeEach
    void seed() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
            .thenReturn(PutObjectResponse.builder().build());

        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        supplierAId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierAId, "Supplier A", now, companyA);
        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "admin-a@example.com", now, companyA);
        // Terms anchored to the BL, 0% deposit (→ a single balance), due 30 days after the BL date.
        jdbcTemplate.update(
            "INSERT INTO payment_terms (id, company_id, supplier_id, terms_type, deposit_pct, anchor_date_type, days_from_anchor, created_at) "
                + "VALUES (?, ?, ?, 'ZERO_DEPOSIT', NULL, 'BL', 30, ?)",
            supplierAId, companyA, supplierAId, now);
        jdbcTemplate.update("UPDATE suppliers SET current_term_id = ? WHERE id = ?", supplierAId, supplierAId);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM shipment_document_audit_events WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM shipment_consignments WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM shipments WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM payment_audit_events WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM payments WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM po_number_counters WHERE company_id = ?", companyA);
        jdbcTemplate.update("UPDATE suppliers SET current_term_id = NULL, target_term_id = NULL WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM payment_terms WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM sku_prices WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM users WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM companies WHERE id = ?", companyA);
    }

    @Test
    void loggingABlSetsTheBalanceDueDateThroughTheAnchorSeam() throws Exception {
        UUID poId = generatedPoWithBalance();

        // Before: balance exists, anchored to BL, due date not yet known.
        assertThat(balanceDueDate(poId)).isNull();

        // Log the BL — the anchor event fires, 6.2 calculates: BL date + 30 days.
        logBl(poId, "2026-09-01");

        assertThat(balanceDueDate(poId)).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void correctingTheBlDateRecalculatesAndAuditsTheDueDate() throws Exception {
        UUID poId = generatedPoWithBalance();
        logBl(poId, "2026-09-01");
        assertThat(balanceDueDate(poId)).isEqualTo(LocalDate.of(2026, 10, 1));

        // Correct the BL date — re-publishes; 6.2's re-entrancy moves the due date and audits old→new.
        logBl(poId, "2026-09-10");
        assertThat(balanceDueDate(poId)).isEqualTo(LocalDate.of(2026, 10, 10));

        List<Map<String, Object>> recalcs = jdbcTemplate.queryForList(
            "SELECT detail FROM payment_audit_events WHERE purchase_order_id = ? AND event_type = 'DUE_DATE_RECALCULATED'",
            poId);
        assertThat(recalcs).hasSize(1);
        assertThat(recalcs.get(0).get("detail").toString()).contains("2026-10-01", "2026-10-10");
    }

    private LocalDate balanceDueDate(UUID poId) {
        Date due = jdbcTemplate.queryForObject(
            "SELECT due_date FROM payments WHERE purchase_order_id = ? AND type = 'BALANCE'", Date.class, poId);
        return due == null ? null : due.toLocalDate();
    }

    private void logBl(UUID poId, String blDate) throws Exception {
        mockMvc.perform(multipart("/api/purchase-orders/{po}/shipment/bill-of-lading", poId)
                .file(new MockMultipartFile("file", "bl.pdf", "application/pdf", "bl".getBytes(StandardCharsets.UTF_8)))
                .param("blReference", "BL-001").param("blDate", blDate)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isCreated());
    }

    private UUID generatedPoWithBalance() throws Exception {
        UUID poId = createPo();
        UUID skuId = seedSku();
        seedPrice(skuId, "2.0000", LocalDate.now().minusDays(1));
        addLine(poId, skuId, 10);
        setEtd(poId, LocalDate.now().plusDays(14));
        mockMvc.perform(post("/api/purchase-orders/{id}/generate", poId)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId))
            .andExpect(status().isOk());
        return poId;
    }

    private UUID createPo() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/purchase-orders")
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"supplierId\":\"" + supplierAId + "\"}"))
            .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private void addLine(UUID poId, UUID skuId, int quantity) throws Exception {
        mockMvc.perform(post("/api/purchase-orders/{id}/lines", poId)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuId\":\"" + skuId + "\",\"quantity\":" + quantity + "}"))
            .andExpect(status().isCreated());
    }

    private void setEtd(UUID poId, LocalDate etd) throws Exception {
        mockMvc.perform(put("/api/purchase-orders/{id}/etd", poId)
                .header(TENANT_HEADER, companyA).header(USER_HEADER, userAId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestedEtd\":\"" + etd + "\"}"))
            .andExpect(status().isOk());
    }

    private UUID seedSku() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, description, status, created_at, company_id) "
                + "VALUES (?, ?, ?, 'Widget', 'ACTIVE', ?, ?)",
            id, supplierAId, "SKU-" + id, Timestamp.from(Instant.now()), companyA);
        return id;
    }

    private void seedPrice(UUID skuId, String amount, LocalDate validFrom) {
        jdbcTemplate.update(
            "INSERT INTO sku_prices (id, sku_id, unit_price_amount, currency, valid_from, valid_to, created_at, company_id) "
                + "VALUES (?, ?, ?, 'USD', ?, NULL, ?, ?)",
            UUID.randomUUID(), skuId, new BigDecimal(amount), Date.valueOf(validFrom), Timestamp.from(Instant.now()), companyA);
    }
}
