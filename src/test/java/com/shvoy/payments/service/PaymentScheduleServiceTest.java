package com.shvoy.payments.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
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

/**
 * The payment-creation seam in isolation (Story 6.1) — publishes {@link
 * PurchaseOrderGeneratedEvent} directly to exercise every split shape without
 * driving the full generation machinery. No class-level @Transactional; seed
 * via JDBC and set {@code TenantContext} per call, same as the repository
 * isolation tests.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentScheduleServiceTest {

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID poAId;
    UUID poBId;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        poAId = seedPo(companyA);
        poBId = seedPo(companyB);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM payments WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private UUID seedPo(UUID companyId) {
        Timestamp now = Timestamp.from(Instant.now());
        UUID supplierId = UUID.randomUUID();
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

    private static Money usd(String amount) {
        return new Money(new BigDecimal(amount), "USD");
    }

    private List<Payment> paymentsUnder(UUID company, Runnable action) {
        TenantContext.set(company);
        try {
            action.run();
            return paymentRepository.findAll().stream()
                .filter(p -> p.getPurchaseOrderId().equals(company.equals(companyA) ? poAId : poBId))
                .toList();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aDepositPoProducesADepositAndABalancePayment() {
        List<Payment> payments = paymentsUnder(companyA, () -> eventPublisher.publishEvent(
            new PurchaseOrderGeneratedEvent(poAId, usd("100.00"), usd("30.00"), usd("70.00"))));

        assertThat(payments).hasSize(2);
        assertThat(payments).extracting(Payment::getType)
            .containsExactlyInAnyOrder(PaymentType.DEPOSIT, PaymentType.BALANCE);
        assertThat(payments).allSatisfy(p -> {
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(p.getDueDate()).isNull(); // due dates are 6.2's job
        });
        Payment deposit = payments.stream().filter(p -> p.getType() == PaymentType.DEPOSIT).findFirst().orElseThrow();
        assertThat(deposit.getAmount().amount()).isEqualByComparingTo("30.00");
    }

    @Test
    void aZeroDepositPoProducesOnlyABalancePayment() {
        List<Payment> payments = paymentsUnder(companyA, () -> eventPublisher.publishEvent(
            new PurchaseOrderGeneratedEvent(poAId, usd("100.00"), usd("0.00"), usd("100.00"))));

        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getType()).isEqualTo(PaymentType.BALANCE);
        assertThat(payments.get(0).getAmount().amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void aPoWithNoPaymentTermsProducesASingleBalanceForTheFullTotal() {
        List<Payment> payments = paymentsUnder(companyA, () -> eventPublisher.publishEvent(
            new PurchaseOrderGeneratedEvent(poAId, usd("50.00"), null, null)));

        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getType()).isEqualTo(PaymentType.BALANCE);
        assertThat(payments.get(0).getAmount().amount()).isEqualByComparingTo("50.00");
    }

    @Test
    void thePaymentAmountsSumToThePoTotalExactlyOnAnOddSplit() {
        // 30% of 100.01 → deposit 30.00, balance absorbs the remainder 70.01 (deposit + balance == total exactly).
        List<Payment> payments = paymentsUnder(companyA, () -> eventPublisher.publishEvent(
            new PurchaseOrderGeneratedEvent(poAId, usd("100.01"), usd("30.00"), usd("70.01"))));

        BigDecimal sum = payments.stream()
            .map(p -> p.getAmount().amount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("100.01");
    }

    @Test
    void paymentsAreTenantIsolated() {
        paymentsUnder(companyA, () -> eventPublisher.publishEvent(
            new PurchaseOrderGeneratedEvent(poAId, usd("100.00"), usd("30.00"), usd("70.00"))));

        TenantContext.set(companyB);
        try {
            // Company B sees none of Company A's payments.
            assertThat(paymentRepository.findAll()).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }
}
