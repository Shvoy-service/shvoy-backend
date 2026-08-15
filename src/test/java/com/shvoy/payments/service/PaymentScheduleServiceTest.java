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

import com.shvoy.Money;
import com.shvoy.TenantContext;
import com.shvoy.payments.domain.Payment;
import com.shvoy.payments.domain.PaymentStatus;
import com.shvoy.payments.domain.PaymentType;
import com.shvoy.payments.repository.PaymentRepository;
import com.shvoy.purchaseorders.event.PurchaseOrderGeneratedEvent;
import com.shvoy.suppliers.domain.AnchorEvent;

/**
 * Payment creation + terms snapshot (Story 6.1/6.2) — publishes {@link
 * PurchaseOrderGeneratedEvent} directly. Seed via JDBC, set {@code
 * TenantContext} per call.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentScheduleServiceTest {

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    PaymentDueDateService paymentDueDateService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID supplierAId;
    UUID poAId;
    UUID poBId;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        supplierAId = UUID.randomUUID();
        poAId = seedSupplierAndPo(companyA, supplierAId);
        poBId = seedSupplierAndPo(companyB, UUID.randomUUID());
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM payment_audit_events WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM payments WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("UPDATE suppliers SET current_term_id = NULL, target_term_id = NULL WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM payment_terms WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private UUID seedSupplierAndPo(UUID companyId, UUID supplierId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierId, "Supplier", now, companyId);
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            userId, "u-" + userId + "@example.com", now, companyId);
        UUID poId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, ?, 'GENERATED', ?, ?, ?)",
            poId, supplierId, "PO-" + poId, userId, now, companyId);
        return poId;
    }

    private void seedTerms(UUID companyId, UUID supplierId, String depositPct, String anchorEvent, int daysOffset) {
        boolean zero = new BigDecimal(depositPct).signum() == 0;
        jdbcTemplate.update(
            "INSERT INTO payment_terms (id, supplier_id, company_id, terms_type, deposit_pct, anchor_date_type, days_from_anchor, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            supplierId, supplierId, companyId, zero ? "ZERO_DEPOSIT" : "DEPOSIT_BALANCE",
            zero ? null : new BigDecimal(depositPct), anchorEvent, daysOffset, Timestamp.from(Instant.now()));
        jdbcTemplate.update("UPDATE suppliers SET current_term_id = ? WHERE id = ?", supplierId, supplierId);
    }

    private static Money usd(String amount) {
        return new Money(new BigDecimal(amount), "USD");
    }

    private final LocalDate generationDate = LocalDate.of(2026, 3, 1);

    private List<Payment> paymentsFor(UUID company, UUID poId, Runnable action) {
        TenantContext.set(company);
        try {
            action.run();
            return paymentRepository.findAll().stream().filter(p -> p.getPurchaseOrderId().equals(poId)).toList();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aDepositPoBornsADepositDueAtGenerationAndABalanceAwaitingItsAnchor() {
        seedTerms(companyA, supplierAId, "30.0", "ARRIVAL", 30);
        List<Payment> payments = paymentsFor(companyA, poAId, () -> eventPublisher.publishEvent(
            new PurchaseOrderGeneratedEvent(poAId, supplierAId, generationDate, usd("100.00"), usd("30.00"), usd("70.00"))));

        assertThat(payments).hasSize(2);
        Payment deposit = payments.stream().filter(p -> p.getType() == PaymentType.DEPOSIT).findFirst().orElseThrow();
        assertThat(deposit.getDueDate()).isEqualTo(generationDate); // deposit due at generation
        assertThat(deposit.getAnchorEvent()).isNull();

        Payment balance = payments.stream().filter(p -> p.getType() == PaymentType.BALANCE).findFirst().orElseThrow();
        assertThat(balance.getDueDate()).isNull(); // awaiting its anchor date
        assertThat(balance.getAnchorEvent()).isEqualTo(AnchorEvent.ARRIVAL); // snapshotted terms
        assertThat(balance.getDaysOffset()).isEqualTo(30);
        assertThat(balance.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void aZeroDepositPoProducesOnlyABalance() {
        seedTerms(companyA, supplierAId, "0.0", "BL", 60);
        List<Payment> payments = paymentsFor(companyA, poAId, () -> eventPublisher.publishEvent(
            new PurchaseOrderGeneratedEvent(poAId, supplierAId, generationDate, usd("100.00"), usd("0.00"), usd("100.00"))));

        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getType()).isEqualTo(PaymentType.BALANCE);
        assertThat(payments.get(0).getAmount().amount()).isEqualByComparingTo("100.00");
        assertThat(payments.get(0).getAnchorEvent()).isEqualTo(AnchorEvent.BL);
    }

    @Test
    void aPoWithNoTermsProducesASingleBalanceWithNoAnchor() {
        List<Payment> payments = paymentsFor(companyA, poAId, () -> eventPublisher.publishEvent(
            new PurchaseOrderGeneratedEvent(poAId, supplierAId, generationDate, usd("50.00"), null, null)));

        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getType()).isEqualTo(PaymentType.BALANCE);
        assertThat(payments.get(0).getAnchorEvent()).isNull(); // no terms → no anchor → due date stays null
        assertThat(payments.get(0).getDueDate()).isNull();
    }

    @Test
    void amountsSumToTheOrderTotalOnAnOddSplit() {
        seedTerms(companyA, supplierAId, "30.0", "ARRIVAL", 30);
        List<Payment> payments = paymentsFor(companyA, poAId, () -> eventPublisher.publishEvent(
            new PurchaseOrderGeneratedEvent(poAId, supplierAId, generationDate, usd("100.01"), usd("30.00"), usd("70.01"))));

        BigDecimal sum = payments.stream().map(p -> p.getAmount().amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("100.01");
    }

    @Test
    void termsAreSnapshottedAtGenerationSoALaterTermsChangeDoesNotMoveTheDueDate() {
        seedTerms(companyA, supplierAId, "30.0", "ARRIVAL", 30);
        paymentsFor(companyA, poAId, () -> eventPublisher.publishEvent(
            new PurchaseOrderGeneratedEvent(poAId, supplierAId, generationDate, usd("100.00"), usd("30.00"), usd("70.00"))));

        // Change the supplier's terms after the PO was generated.
        jdbcTemplate.update("UPDATE payment_terms SET days_from_anchor = 90 WHERE supplier_id = ?", supplierAId);

        // The anchor date resolves the balance against the SNAPSHOTTED offset (30), not the new one (90).
        TenantContext.set(companyA);
        try {
            paymentDueDateService.applyAnchorEventDate(poAId, AnchorEvent.ARRIVAL, LocalDate.of(2026, 6, 1));
            Payment balance = paymentRepository.findAll().stream()
                .filter(p -> p.getPurchaseOrderId().equals(poAId) && p.getType() == PaymentType.BALANCE)
                .findFirst().orElseThrow();
            assertThat(balance.getDueDate()).isEqualTo(LocalDate.of(2026, 7, 1)); // 2026-06-01 + 30 days, not + 90
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void paymentsAreTenantIsolated() {
        paymentsFor(companyA, poAId, () -> eventPublisher.publishEvent(
            new PurchaseOrderGeneratedEvent(poAId, supplierAId, generationDate, usd("100.00"), null, null)));

        TenantContext.set(companyB);
        try {
            assertThat(paymentRepository.findAll()).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }
}
