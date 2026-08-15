package com.shvoy.payments.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.payments.domain.Payment;
import com.shvoy.payments.domain.PaymentAuditEventType;
import com.shvoy.payments.domain.PaymentType;
import com.shvoy.payments.event.AnchorEventDateKnownEvent;
import com.shvoy.payments.repository.PaymentRepository;
import com.shvoy.suppliers.domain.AnchorEvent;

/**
 * Calculates balance due dates once an anchor event's date becomes known
 * (Story 6.2) — the seam Feature 7 will drive via {@link
 * AnchorEventDateKnownEvent}.
 *
 * <p><strong>Re-entrant by design.</strong> {@link #applyAnchorEventDate} is
 * safe to call again with a revised date: it recalculates and, if the due date
 * actually moves, audits the change (old → new). A date that resolves to the
 * same due date is a no-op. This is the foundation of Phase 2's "recalculate
 * as ETA shifts".
 *
 * <p>Only balance payments whose <em>snapshotted</em> anchor event matches the
 * incoming one are affected — a BL date doesn't touch a payment anchored to
 * arrival. The calculation is deterministic: same terms + same anchor date →
 * same due date.
 */
@Service
public class PaymentDueDateService {

    private final PaymentRepository paymentRepository;
    private final PaymentAuditService paymentAuditService;

    PaymentDueDateService(PaymentRepository paymentRepository, PaymentAuditService paymentAuditService) {
        this.paymentRepository = paymentRepository;
        this.paymentAuditService = paymentAuditService;
    }

    @EventListener
    public void onAnchorEventDateKnown(AnchorEventDateKnownEvent event) {
        applyAnchorEventDate(event.purchaseOrderId(), event.anchorEvent(), event.anchorDate());
    }

    /**
     * Set (or revise) the due dates of a PO's balance payments anchored to
     * {@code anchorEvent}, from its now-known {@code anchorDate}. The internal
     * operation Feature 7 invokes; public so it's directly testable and could
     * be called without the event if ever needed.
     */
    @Transactional
    public void applyAnchorEventDate(UUID purchaseOrderId, AnchorEvent anchorEvent, LocalDate anchorDate) {
        List<Payment> affected = paymentRepository.findAll().stream()
            .filter(p -> p.getPurchaseOrderId().equals(purchaseOrderId))
            .filter(p -> p.getType() == PaymentType.BALANCE)
            .filter(p -> anchorEvent == p.getAnchorEvent())
            .toList();

        for (Payment payment : affected) {
            LocalDate previousDueDate = payment.getDueDate();
            LocalDate newDueDate = anchorDate.plusDays(payment.getDaysOffset());
            if (Objects.equals(previousDueDate, newDueDate)) {
                continue; // deterministic no-op: the same anchor date resolves to the same due date
            }
            payment.applyCalculatedDueDate(anchorDate);
            paymentRepository.save(payment);

            if (previousDueDate == null) {
                paymentAuditService.record(payment.getId(), purchaseOrderId, PaymentAuditEventType.DUE_DATE_SET,
                    "Balance due " + newDueDate + " = " + anchorEvent + " date " + anchorDate
                        + " + " + payment.getDaysOffset() + " days");
            } else {
                paymentAuditService.record(payment.getId(), purchaseOrderId, PaymentAuditEventType.DUE_DATE_RECALCULATED,
                    "Balance due date moved " + previousDueDate + " → " + newDueDate + " because the " + anchorEvent
                        + " date changed to " + anchorDate + " (+ " + payment.getDaysOffset() + " days)");
            }
        }
    }
}
