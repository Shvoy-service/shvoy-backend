package com.shvoy.payments.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.shvoy.Money;
import com.shvoy.payments.domain.Payment;
import com.shvoy.payments.domain.PaymentAuditEventType;
import com.shvoy.payments.repository.PaymentRepository;
import com.shvoy.purchaseorders.event.PurchaseOrderGeneratedEvent;
import com.shvoy.suppliers.dto.PaymentScheduleTerms;

/**
 * Creates a PO's payment obligations when it's generated (Story 6.1) and sets
 * what due dates are already knowable (Story 6.2) — the {@code payments} side
 * of the {@link PurchaseOrderGeneratedEvent} seam.
 *
 * <p><strong>Synchronous listener, on purpose</strong> (see 6.1): runs inline
 * on the publisher's thread inside the generation transaction, so payment
 * creation is atomic with generation and the {@code ThreadLocal} {@code
 * TenantContext} is preserved (an {@code @Async} listener would lose it). It
 * also means resolving the supplier's terms here <em>is</em> the
 * snapshot-at-generation — see {@link EffectivePaymentTermsResolver}.
 *
 * <p>The deposit is born with its due date (the generation date); the balance
 * is born without one, carrying the snapshotted anchor terms so 6.2's seam can
 * fill it in when the anchor date becomes known.
 */
@Service
public class PaymentScheduleService {

    private static final Logger log = LoggerFactory.getLogger(PaymentScheduleService.class);

    private final PaymentRepository paymentRepository;
    private final EffectivePaymentTermsResolver termsResolver;
    private final PaymentAuditService paymentAuditService;

    PaymentScheduleService(PaymentRepository paymentRepository, EffectivePaymentTermsResolver termsResolver,
            PaymentAuditService paymentAuditService) {
        this.paymentRepository = paymentRepository;
        this.termsResolver = termsResolver;
        this.paymentAuditService = paymentAuditService;
    }

    @EventListener
    public void onPurchaseOrderGenerated(PurchaseOrderGeneratedEvent event) {
        Money orderTotal = event.orderTotal();
        if (orderTotal == null) {
            log.warn("PO {} generated with no order total — no payments scheduled", event.purchaseOrderId());
            return;
        }

        // Snapshot the effective terms now (at generation) — a later terms change won't move this PO's due dates.
        Optional<PaymentScheduleTerms> terms = termsResolver.resolveForPurchaseOrder(event.supplierId());

        List<Payment> payments = new ArrayList<>();
        Money deposit = event.deposit();
        if (deposit != null && deposit.amount().signum() > 0) {
            payments.add(Payment.deposit(event.purchaseOrderId(), deposit, event.generationDate()));
            payments.add(balancePayment(event.purchaseOrderId(), event.balance(), terms));
        } else {
            // 0% deposit (balance == total) or no terms → a single balance for the full amount.
            Money fullBalance = event.balance() != null ? event.balance() : orderTotal;
            payments.add(balancePayment(event.purchaseOrderId(), fullBalance, terms));
        }

        for (Payment payment : payments) {
            paymentRepository.save(payment);
            recordDepositDueDate(event, payment);
        }
    }

    private Payment balancePayment(UUID purchaseOrderId, Money amount, Optional<PaymentScheduleTerms> terms) {
        return Payment.balance(purchaseOrderId, amount,
            terms.map(PaymentScheduleTerms::anchorEvent).orElse(null),
            terms.map(PaymentScheduleTerms::daysOffset).orElse(null));
    }

    /** The deposit is the only payment with a due date at creation — record its derivation for auditability. */
    private void recordDepositDueDate(PurchaseOrderGeneratedEvent event, Payment payment) {
        if (payment.getDueDate() != null) {
            paymentAuditService.record(payment.getId(), event.purchaseOrderId(),
                PaymentAuditEventType.DUE_DATE_SET,
                "Deposit due at the PO generation date (" + event.generationDate() + ")");
        }
    }
}
