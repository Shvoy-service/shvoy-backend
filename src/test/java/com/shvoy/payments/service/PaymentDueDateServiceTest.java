package com.shvoy.payments.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.shvoy.TenantContext;
import com.shvoy.payments.domain.Payment;
import com.shvoy.payments.domain.PaymentAuditEvent;
import com.shvoy.payments.domain.PaymentAuditEventType;
import com.shvoy.payments.event.AnchorEventDateKnownEvent;
import com.shvoy.payments.repository.PaymentRepository;
import com.shvoy.suppliers.domain.AnchorEvent;

/**
 * The anchor-date → due-date calculation seam (Story 6.2): each anchor type, a
 * negative offset, anchor matching, the re-entrant recalculation + audit, and
 * the event wiring. A balance payment is seeded directly (with its snapshotted
 * anchor terms) to isolate the calculation.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentDueDateServiceTest {

    @Autowired
    PaymentDueDateService paymentDueDateService;

    @Autowired
    PaymentAuditService paymentAuditService;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    UUID poAId;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        UUID supplierId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierId, "Supplier", now, companyA);
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userId, "u@example.com", now, companyA);
        poAId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, 'PO-1', 'GENERATED', ?, ?, ?)",
            poAId, supplierId, userId, now, companyA);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM payment_audit_events WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM payments WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM users WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id = ?", companyA);
        jdbcTemplate.update("DELETE FROM companies WHERE id = ?", companyA);
    }

    /** Seeds a balance payment awaiting its anchor date (null due date), with snapshotted terms. */
    private UUID seedBalanceAwaitingAnchor(AnchorEvent anchorEvent, int daysOffset) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO payments (id, purchase_order_id, type, amount_amount, currency, due_date, anchor_event, "
                + "days_offset, anchor_date_applied, status, created_at, company_id) "
                + "VALUES (?, ?, 'BALANCE', 70.00, 'USD', NULL, ?, ?, NULL, 'PENDING', ?, ?)",
            id, poAId, anchorEvent.name(), daysOffset, Timestamp.from(Instant.now()), companyA);
        return id;
    }

    private Payment reload(UUID paymentId) {
        return paymentRepository.findAll().stream().filter(p -> p.getId().equals(paymentId)).findFirst().orElseThrow();
    }

    @ParameterizedTest
    @EnumSource(AnchorEvent.class)
    void balanceDueDateIsAnchorDatePlusOffsetForEveryAnchorType(AnchorEvent anchorEvent) {
        UUID paymentId = seedBalanceAwaitingAnchor(anchorEvent, 30);
        TenantContext.set(companyA);
        try {
            paymentDueDateService.applyAnchorEventDate(poAId, anchorEvent, LocalDate.of(2026, 6, 1));
            assertThat(reload(paymentId).getDueDate()).isEqualTo(LocalDate.of(2026, 7, 1)); // + 30 days
            assertThat(reload(paymentId).getAnchorDateApplied()).isEqualTo(LocalDate.of(2026, 6, 1));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aNegativeOffsetMakesTheBalanceDueBeforeTheAnchorDate() {
        UUID paymentId = seedBalanceAwaitingAnchor(AnchorEvent.ARRIVAL, -5); // due 5 days *before* arrival
        TenantContext.set(companyA);
        try {
            paymentDueDateService.applyAnchorEventDate(poAId, AnchorEvent.ARRIVAL, LocalDate.of(2026, 6, 10));
            assertThat(reload(paymentId).getDueDate()).isEqualTo(LocalDate.of(2026, 6, 5));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void anAnchorDateForADifferentEventLeavesThePaymentUntouched() {
        UUID paymentId = seedBalanceAwaitingAnchor(AnchorEvent.ARRIVAL, 30);
        TenantContext.set(companyA);
        try {
            paymentDueDateService.applyAnchorEventDate(poAId, AnchorEvent.BL, LocalDate.of(2026, 6, 1)); // BL, not ARRIVAL
            assertThat(reload(paymentId).getDueDate()).isNull(); // still awaiting its own anchor
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aRevisedAnchorDateRecalculatesAndAuditsTheChange() {
        UUID paymentId = seedBalanceAwaitingAnchor(AnchorEvent.ARRIVAL, 30);
        TenantContext.set(companyA);
        try {
            paymentDueDateService.applyAnchorEventDate(poAId, AnchorEvent.ARRIVAL, LocalDate.of(2026, 6, 1));
            assertThat(reload(paymentId).getDueDate()).isEqualTo(LocalDate.of(2026, 7, 1));

            // The arrival date is revised later — recalculate.
            paymentDueDateService.applyAnchorEventDate(poAId, AnchorEvent.ARRIVAL, LocalDate.of(2026, 6, 15));
            assertThat(reload(paymentId).getDueDate()).isEqualTo(LocalDate.of(2026, 7, 15));

            List<PaymentAuditEvent> trail = paymentAuditService.trailFor(paymentId);
            assertThat(trail).extracting(PaymentAuditEvent::getEventType).containsExactly(
                PaymentAuditEventType.DUE_DATE_SET, PaymentAuditEventType.DUE_DATE_RECALCULATED);
            assertThat(trail.get(1).getDetail()).contains("2026-07-01").contains("2026-07-15");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void theAnchorDateKnownEventDrivesTheCalculation() {
        UUID paymentId = seedBalanceAwaitingAnchor(AnchorEvent.INVOICE, 45);
        TenantContext.set(companyA);
        try {
            eventPublisher.publishEvent(new AnchorEventDateKnownEvent(poAId, AnchorEvent.INVOICE, LocalDate.of(2026, 6, 1)));
            assertThat(reload(paymentId).getDueDate()).isEqualTo(LocalDate.of(2026, 7, 16)); // + 45 days
        } finally {
            TenantContext.clear();
        }
    }
}
