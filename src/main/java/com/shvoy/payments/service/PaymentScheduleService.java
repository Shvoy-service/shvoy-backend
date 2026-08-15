package com.shvoy.payments.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.shvoy.Money;
import com.shvoy.payments.domain.Payment;
import com.shvoy.payments.domain.PaymentType;
import com.shvoy.payments.repository.PaymentRepository;
import com.shvoy.purchaseorders.event.PurchaseOrderGeneratedEvent;

/**
 * Creates a PO's payment obligations when it's generated (Story 6.1) — the
 * {@code payments} side of the {@link PurchaseOrderGeneratedEvent} seam. This
 * is the whole point of the event: {@code purchaseorders} publishes a fact
 * about itself, and this module reacts, with no dependency the other way.
 *
 * <p><strong>Synchronous listener, on purpose.</strong> A plain {@code
 * @EventListener} runs inline on the publisher's thread, inside the generation
 * transaction — so payment creation commits atomically with generation (both
 * or neither), and, critically, the tenant is preserved: {@code TenantContext}
 * is a {@code ThreadLocal}, so an {@code @Async} listener (a different thread)
 * would lose it and the {@code @TenantId} insert would fail. This is not {@code
 * @ApplicationModuleListener} for exactly that reason.
 *
 * <p><strong>What gets created.</strong> A {@code DEPOSIT} + a {@code BALANCE}
 * payment when the split has a positive deposit; otherwise a single {@code
 * BALANCE} for the full amount — which covers both the confirmed 0%-deposit
 * rule and a PO with no payment terms configured (no split at all). The amounts
 * are snapshotted from the event (the 4.3 split); the invariant "a PO's payment
 * amounts sum to its order total" holds by construction.
 */
@Service
public class PaymentScheduleService {

    private static final Logger log = LoggerFactory.getLogger(PaymentScheduleService.class);

    private final PaymentRepository paymentRepository;

    PaymentScheduleService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @EventListener
    public void onPurchaseOrderGenerated(PurchaseOrderGeneratedEvent event) {
        Money orderTotal = event.orderTotal();
        if (orderTotal == null) {
            // No priced lines → nothing to schedule. A GENERATED PO always has a total, so this is defensive.
            log.warn("PO {} generated with no order total — no payments scheduled", event.purchaseOrderId());
            return;
        }

        Money deposit = event.deposit();
        List<Payment> payments = new ArrayList<>();
        if (deposit != null && deposit.amount().signum() > 0) {
            payments.add(new Payment(event.purchaseOrderId(), PaymentType.DEPOSIT, deposit));
            payments.add(new Payment(event.purchaseOrderId(), PaymentType.BALANCE, event.balance()));
        } else {
            // 0% deposit (balance == total) or no terms (no split) → a single balance for the full amount.
            Money fullBalance = event.balance() != null ? event.balance() : orderTotal;
            payments.add(new Payment(event.purchaseOrderId(), PaymentType.BALANCE, fullBalance));
        }
        paymentRepository.saveAll(payments);
    }
}
