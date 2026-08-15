package com.shvoy.payments;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * Story 6.1's headline acceptance criterion, end to end: generating a PO
 * actually creates its payment records, through the {@code
 * PurchaseOrderGeneratedEvent} seam — no direct call from {@code
 * purchaseorders} into {@code payments}. Drives the real generate endpoint
 * (S3 mocked, same as PurchaseOrderGenerationControllerTest); the PO has no
 * payment terms, so the split is a single balance for the full total.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentGenerationSeamTest {

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
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        supplierAId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierAId, "Supplier A", now, companyA);
        userAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userAId, "admin-a@example.com", now, companyA);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM payments WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM po_number_counters WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM sku_prices WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM users WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM companies WHERE id = ?", companyA);
    }

    @Test
    @WithMockUser(roles = "PURCHASING")
    void generatingAPoCreatesItsPaymentRecordsViaTheEvent() throws Exception {
        UUID poId = createPo();
        UUID skuId = seedSku();
        seedPrice(skuId, "2.0000", LocalDate.now().minusDays(1));
        addLine(poId, skuId, 10);
        setEtd(poId, LocalDate.now().plusDays(14));

        mockMvc.perform(post("/api/purchase-orders/{id}/generate", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("GENERATED"))
            .andExpect(jsonPath("$.orderTotal.amount").value("20.00"));

        // The seam fired: a payment now exists for the PO — a single BALANCE of the full total (no terms → no split).
        List<Map<String, Object>> payments = jdbcTemplate.queryForList(
            "SELECT type, amount_amount, currency, status, due_date FROM payments WHERE purchase_order_id = ?", poId);
        org.assertj.core.api.Assertions.assertThat(payments).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(payments.get(0)).containsEntry("type", "BALANCE")
            .containsEntry("status", "PENDING")
            .containsEntry("currency", "USD");
        org.assertj.core.api.Assertions.assertThat(new BigDecimal(payments.get(0).get("amount_amount").toString()))
            .isEqualByComparingTo("20.00");
        org.assertj.core.api.Assertions.assertThat(payments.get(0).get("due_date")).isNull(); // 6.2's job
    }

    private UUID createPo() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/purchase-orders")
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"supplierId\":\"" + supplierAId + "\"}"))
            .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private void addLine(UUID poId, UUID skuId, int quantity) throws Exception {
        mockMvc.perform(post("/api/purchase-orders/{id}/lines", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuId\":\"" + skuId + "\",\"quantity\":" + quantity + "}"))
            .andExpect(status().isCreated());
    }

    private void setEtd(UUID poId, LocalDate etd) throws Exception {
        mockMvc.perform(put("/api/purchase-orders/{id}/etd", poId)
                .header(TENANT_HEADER, companyA.toString())
                .header(USER_HEADER, userAId.toString())
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
