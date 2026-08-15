package com.shvoy.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Date;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.shvoy.ConflictException;
import com.shvoy.CurrentUserContext;
import com.shvoy.Money;
import com.shvoy.TenantContext;
import com.shvoy.payments.domain.CreditCause;
import com.shvoy.payments.domain.CreditLedgerAuditEvent;
import com.shvoy.payments.domain.CreditLedgerAuditEventType;
import com.shvoy.payments.domain.CreditLedgerStatus;
import com.shvoy.payments.dto.CreditLedgerEntryResponse;
import com.shvoy.payments.dto.CreditMatchOutcome;
import com.shvoy.payments.dto.CreditMatchResult;
import com.shvoy.payments.dto.LogCreditRequest;

/**
 * The credit ledger's rule and lifecycle in isolation (Story 6.7): the
 * match-check, apply-once, and the audit trail. Service-level because {@code
 * checkClaim}/{@code apply} are the reusable operations 6.5 will call, not
 * endpoints. Seed via JDBC; set {@code TenantContext}/{@code CurrentUserContext}
 * per call.
 */
@SpringBootTest
@ActiveProfiles("test")
class CreditLedgerServiceTest {

    @Autowired
    CreditLedgerService creditLedgerService;

    @Autowired
    CreditLedgerAuditService creditLedgerAuditService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final UUID companyA = UUID.randomUUID();
    final UUID companyB = UUID.randomUUID();
    UUID userAId;
    UUID poAId;
    UUID otherPoAId;
    UUID invoiceAId;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyA, "Co A", now);
        jdbcTemplate.update("INSERT INTO companies (id, name, created_at) VALUES (?, ?, ?)", companyB, "Co B", now);
        userAId = seedUser(companyA);
        poAId = seedPo(companyA);
        otherPoAId = seedPo(companyA);
        invoiceAId = seedInvoice(companyA, poAId);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM credit_ledger_audit_events WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM credit_ledger_entries WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM invoices WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM purchase_orders WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM users WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM suppliers WHERE company_id IN (?, ?)", companyA, companyB);
        jdbcTemplate.update("DELETE FROM companies WHERE id IN (?, ?)", companyA, companyB);
    }

    private UUID seedUser(UUID companyId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, email, role, status, created_at, company_id) VALUES (?, ?, 'ADMIN', 'ACTIVE', ?, ?)",
            id, "u-" + id + "@example.com", Timestamp.from(Instant.now()), companyId);
        return id;
    }

    private UUID seedPo(UUID companyId) {
        Timestamp now = Timestamp.from(Instant.now());
        UUID supplierId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO suppliers (id, name, status, created_at, company_id) VALUES (?, ?, 'ACTIVE', ?, ?)",
            supplierId, "Supplier-" + supplierId, now, companyId);
        UUID poId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO purchase_orders (id, supplier_id, po_number, status, created_by, created_at, company_id) "
                + "VALUES (?, ?, ?, 'GENERATED', ?, ?, ?)",
            poId, supplierId, "PO-" + poId, userAId == null ? seedUser(companyId) : userAId, now, companyId);
        return poId;
    }

    private UUID seedInvoice(UUID companyId, UUID poId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO invoices (id, purchase_order_id, invoice_reference, amount_amount, currency, invoice_date, "
                + "status, active, logged_by, created_at, company_id) "
                + "VALUES (?, ?, 'INV-1', 100.00, 'USD', ?, 'LOGGED', true, ?, ?, ?)",
            id, poId, Date.valueOf(LocalDate.of(2026, 3, 1)), userAId, Timestamp.from(Instant.now()), companyId);
        return id;
    }

    private CreditLedgerEntryResponse logCredit(UUID poId, String amount) {
        return creditLedgerService.log(new LogCreditRequest(
            poId, new BigDecimal(amount), "USD", CreditCause.SHORT_SHIPMENT, null, null, null));
    }

    private <T> T asTenant(UUID company, java.util.function.Supplier<T> action) {
        TenantContext.set(company);
        CurrentUserContext.set(userAId);
        try {
            return action.get();
        } finally {
            CurrentUserContext.clear();
            TenantContext.clear();
        }
    }

    @Test
    void checkClaimMatchesAnOpenEntryWithTheExactAmountAndPo() {
        asTenant(companyA, () -> {
            UUID entryId = logCredit(poAId, "50.00").id();

            CreditMatchResult exact = creditLedgerService.checkClaim(poAId, new Money(new BigDecimal("50.00"), "USD"));
            assertThat(exact.matched()).isTrue();
            assertThat(exact.matchedEntryId()).isEqualTo(entryId);
            assertThat(exact.outcome()).isEqualTo(CreditMatchOutcome.MATCHED);
            return null;
        });
    }

    @Test
    void checkClaimDoesNotMatchAWrongAmountOrAWrongPo() {
        asTenant(companyA, () -> {
            logCredit(poAId, "50.00");

            assertThat(creditLedgerService.checkClaim(poAId, new Money(new BigDecimal("55.00"), "USD")).outcome())
                .isEqualTo(CreditMatchOutcome.AMOUNT_MISMATCH); // an OPEN entry exists for the PO but no exact amount
            assertThat(creditLedgerService.checkClaim(otherPoAId, new Money(new BigDecimal("50.00"), "USD")).outcome())
                .isEqualTo(CreditMatchOutcome.NO_OPEN_CREDIT); // no OPEN entry for that PO
            return null;
        });
    }

    @Test
    void anEntryAppliesExactlyOnceAndNeverMatchesAgain() {
        asTenant(companyA, () -> {
            UUID entryId = logCredit(poAId, "50.00").id();

            CreditLedgerEntryResponse applied = creditLedgerService.apply(entryId, invoiceAId);
            assertThat(applied.status()).isEqualTo(CreditLedgerStatus.APPLIED);
            assertThat(applied.targetInvoiceId()).isEqualTo(invoiceAId);

            // Already applied → no longer matches.
            assertThat(creditLedgerService.checkClaim(poAId, new Money(new BigDecimal("50.00"), "USD")).outcome())
                .isEqualTo(CreditMatchOutcome.NO_OPEN_CREDIT);
            // And cannot be applied a second time.
            assertThatThrownBy(() -> creditLedgerService.apply(entryId, invoiceAId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not OPEN");

            List<CreditLedgerAuditEvent> trail = creditLedgerAuditService.trailFor(entryId);
            assertThat(trail).extracting(CreditLedgerAuditEvent::getEventType)
                .containsExactly(CreditLedgerAuditEventType.LOGGED, CreditLedgerAuditEventType.APPLIED);
            return null;
        });
    }

    @Test
    void cancellingRequiresAReasonAndIsAudited() {
        asTenant(companyA, () -> {
            UUID entryId = logCredit(poAId, "50.00").id();

            creditLedgerService.cancel(entryId, "supplier waived the deduction");
            assertThat(creditLedgerService.get(entryId).status()).isEqualTo(CreditLedgerStatus.CANCELLED);
            assertThat(creditLedgerService.get(entryId).closureReason()).isEqualTo("supplier waived the deduction");

            List<CreditLedgerAuditEvent> trail = creditLedgerAuditService.trailFor(entryId);
            assertThat(trail).extracting(CreditLedgerAuditEvent::getEventType)
                .containsExactly(CreditLedgerAuditEventType.LOGGED, CreditLedgerAuditEventType.CANCELLED);
            return null;
        });
    }

    @Test
    void entriesAreTenantIsolated() {
        UUID entryId = asTenant(companyA, () -> logCredit(poAId, "50.00").id());

        TenantContext.set(companyB);
        try {
            // Company B sees none of A's ledger, and can't match against it.
            assertThat(creditLedgerService.checkClaim(poAId, new Money(new BigDecimal("50.00"), "USD")).outcome())
                .isEqualTo(CreditMatchOutcome.NO_OPEN_CREDIT);
            assertThatThrownBy(() -> creditLedgerService.get(entryId))
                .isInstanceOf(com.shvoy.NotFoundException.class);
        } finally {
            TenantContext.clear();
        }
    }
}
