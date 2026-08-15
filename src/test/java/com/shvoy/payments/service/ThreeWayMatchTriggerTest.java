package com.shvoy.payments.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.shvoy.CurrentUserContext;
import com.shvoy.TenantContext;
import com.shvoy.payments.dto.LogInvoiceRequest;
import com.shvoy.payments.event.ProvisionalGoodsReceiptEvent;
import com.shvoy.payments.event.ProvisionalGoodsReceiptLine;
import com.shvoy.reconciliation.event.ProformaInvoiceConfirmedEvent;

/**
 * Story 6.5 — proof the match is genuinely event-driven end to end: each trigger
 * (invoice logged, GRN receipted, PI confirmed) flips a PENDING balance whose
 * other legs are already in place to READY_TO_PAY, through the real {@link
 * MatchTriggerListener}. This is the wiring per-side unit tests can't vouch for.
 */
@SpringBootTest
@ActiveProfiles("test")
class ThreeWayMatchTriggerTest {

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Autowired
    com.shvoy.payments.service.InvoiceService invoiceService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID company = UUID.randomUUID();
    UUID userId;
    UUID supplierId;
    UUID skuId;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", company, "Co", now);
        userId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userId, "u@x.com", now, company);
        supplierId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierId, "Sup", now, company);
        skuId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO skus (id, supplier_id, code, description, status, created_at, company_id) "
                + "VALUES (?, ?, 'SKU-1', 'Widget', 'ACTIVE', ?, ?)",
            skuId, supplierId, now, company);
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        CurrentUserContext.clear();
        jdbcTemplate.update("DELETE FROM payment_audit_events WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM payment_grn_projection_lines WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM invoices WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM proforma_invoice_lines WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM proforma_invoices WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM payments WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM purchase_order_lines WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM skus WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM users WHERE company_id = ?", company);
        jdbcTemplate.update("DELETE FROM companies WHERE id = ?", company);
    }

    @Test
    void loggingAnInvoiceTriggersTheMatch() {
        UUID po = seedPoWithBalance();
        seedConfirmedPi(po);
        seedGrnProjection(po, 10);
        UUID balance = balanceId(po);

        TenantContext.set(company);
        CurrentUserContext.set(userId);
        invoiceService.log(po, new LogInvoiceRequest(
            "INV-1", new BigDecimal("20.00"), "USD", LocalDate.now(), null, null,
            com.shvoy.payments.domain.InvoiceCoversType.AMOUNT, null, null));

        assertThat(statusOf(balance)).isEqualTo("READY_TO_PAY");
    }

    @Test
    void theGrnEventProjectsAndTriggersTheMatch() {
        UUID po = seedPoWithBalance();
        seedConfirmedPi(po);
        seedInvoice(po, "20.00");
        UUID balance = balanceId(po);

        TenantContext.set(company);
        CurrentUserContext.set(userId);
        eventPublisher.publishEvent(new ProvisionalGoodsReceiptEvent(
            po, UUID.randomUUID(), List.of(new ProvisionalGoodsReceiptLine(skuId, 10))));

        assertThat(projectionCount(po)).isEqualTo(1);
        assertThat(statusOf(balance)).isEqualTo("READY_TO_PAY");
    }

    @Test
    void theConfirmedPiEventTriggersTheMatch() {
        UUID po = seedPoWithBalance();
        seedConfirmedPi(po);
        seedGrnProjection(po, 10);
        seedInvoice(po, "20.00");
        UUID balance = balanceId(po);

        TenantContext.set(company);
        CurrentUserContext.set(userId);
        eventPublisher.publishEvent(new ProformaInvoiceConfirmedEvent(po));

        assertThat(statusOf(balance)).isEqualTo("READY_TO_PAY");
    }

    // --- seed helpers (single company, one SKU, quantity 10 @ 2.0000 = 20.00) ---

    private UUID seedPoWithBalance() {
        UUID po = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, generated_at, company_id) "
                + "VALUES (?, ?, ?, 'GENERATED', ?, ?, ?, ?)",
            po, supplierId, "PO-" + po, userId, now, now, company);
        jdbcTemplate.update(
            "INSERT INTO purchase_order_lines "
                + "(id, company_id, purchase_order_id, sku_id, line_number, quantity, unit_price_amount, currency, price_found, created_at) "
                + "VALUES (?, ?, ?, ?, 1, 10, 2.0000, 'USD', TRUE, ?)",
            UUID.randomUUID(), company, po, skuId, now);
        jdbcTemplate.update(
            "INSERT INTO payments (id, company_id, purchase_order_id, type, amount_amount, currency, status, created_at, anchor_event, days_offset) "
                + "VALUES (?, ?, ?, 'BALANCE', 20.00, 'USD', 'PENDING', ?, 'BL', 30)",
            UUID.randomUUID(), company, po, now);
        return po;
    }

    private void seedConfirmedPi(UUID po) {
        UUID piId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
            "INSERT INTO proforma_invoices (id, purchase_order_id, pi_reference, currency, status, active, logged_by, created_at, company_id) "
                + "VALUES (?, ?, ?, 'USD', 'AUTO_CONFIRMED', TRUE, ?, ?, ?)",
            piId, po, "PI-" + piId, userId, now, company);
        jdbcTemplate.update(
            "INSERT INTO proforma_invoice_lines (id, company_id, proforma_invoice_id, sku_id, line_number, confirmed_unit_price_amount, confirmed_quantity, created_at) "
                + "VALUES (?, ?, ?, ?, 1, 2.0000, 10, ?)",
            UUID.randomUUID(), company, piId, skuId, now);
    }

    private void seedInvoice(UUID po, String amount) {
        jdbcTemplate.update(
            "INSERT INTO invoices (id, company_id, purchase_order_id, invoice_reference, amount_amount, currency, invoice_date, status, active, logged_by, created_at) "
                + "VALUES (?, ?, ?, 'INV-1', ?, 'USD', ?, 'LOGGED', TRUE, ?, ?)",
            UUID.randomUUID(), company, po, new BigDecimal(amount), java.sql.Date.valueOf(LocalDate.now()), userId,
            Timestamp.from(Instant.now()));
    }

    private void seedGrnProjection(UUID po, int qty) {
        jdbcTemplate.update(
            "INSERT INTO payment_grn_projection_lines (id, company_id, purchase_order_id, consignment_id, sku_id, received_quantity, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), company, po, UUID.randomUUID(), skuId, qty, Timestamp.from(Instant.now()));
    }

    private UUID balanceId(UUID po) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM payments WHERE purchase_order_id = ? AND type = 'BALANCE'", UUID.class, po);
    }

    private String statusOf(UUID paymentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    private int projectionCount(UUID po) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_grn_projection_lines WHERE purchase_order_id = ?", Integer.class, po);
    }
}
