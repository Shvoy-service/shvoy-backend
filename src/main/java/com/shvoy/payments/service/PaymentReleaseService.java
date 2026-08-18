package com.shvoy.payments.service;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.ErrorCode;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.payments.domain.Payment;
import com.shvoy.payments.domain.PaymentAuditEventType;
import com.shvoy.payments.domain.PaymentStatus;
import com.shvoy.payments.dto.HoldPaymentRequest;
import com.shvoy.payments.dto.PayPaymentRequest;
import com.shvoy.payments.dto.PaymentResponse;
import com.shvoy.payments.dto.ReleaseHoldRequest;
import com.shvoy.payments.repository.PaymentRepository;

/**
 * Story 6.8 — the human decision at the end of the pipeline: Finance marks a
 * {@code READY_TO_PAY} payment as paid, or holds it (and later releases the
 * hold). This closes the loop, so the full lifecycle runs end to end: PO → PI →
 * GRN → match → release. In the {@code payments} module.
 *
 * <p><strong>SHVOY records the payment decision; it does not move money.</strong>
 * "Pay" is a status fact ("we are paying / have paid this"), not a bank transfer.
 *
 * <p>Each action enforces its own precondition here with a distinct, stable code
 * (the domain's {@link PaymentStatus#canTransitionTo} guard is defense-in-depth
 * behind these). {@code PAID} is terminal — no un-pay path exists, deliberately.
 * The Pay/Hold/Release reasons and the paid record all land on the immutable
 * payment audit trail.
 */
@Service
public class PaymentReleaseService {

    private final PaymentRepository paymentRepository;
    private final PaymentAuditService paymentAuditService;
    private final ApplicationEventPublisher eventPublisher;

    PaymentReleaseService(PaymentRepository paymentRepository, PaymentAuditService paymentAuditService,
            ApplicationEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.paymentAuditService = paymentAuditService;
        this.eventPublisher = eventPublisher;
    }

    /** Pay: {@code READY_TO_PAY → PAID}, terminal. Optional reference + overridable paid date; audited; fires a PAID event. */
    @Transactional
    public PaymentResponse pay(UUID paymentId, PayPaymentRequest request) {
        Payment payment = findOwnPayment(paymentId);
        if (payment.getStatus() != PaymentStatus.READY_TO_PAY) {
            throw new ConflictException(ErrorCode.PAYMENT_NOT_PAYABLE, notReadyMessage("paid", payment));
        }
        LocalDate paidDate = request.paidDate() != null ? request.paidDate() : LocalDate.now();
        payment.pay(paidDate, request.paymentReference());
        paymentRepository.save(payment);
        paymentAuditService.record(payment.getId(), payment.getPurchaseOrderId(), PaymentAuditEventType.PAID,
            "Recorded as paid on " + paidDate
                + (request.paymentReference() == null ? "" : " (ref: " + request.paymentReference() + ")"));
        eventPublisher.publishEvent(new PaymentPaidEvent(payment.getPurchaseOrderId(), payment.getId()));
        return toResponse(payment);
    }

    /** Hold: {@code READY_TO_PAY → ON_HOLD}, mandatory reason, audited. */
    @Transactional
    public PaymentResponse hold(UUID paymentId, HoldPaymentRequest request) {
        Payment payment = findOwnPayment(paymentId);
        if (payment.getStatus() != PaymentStatus.READY_TO_PAY) {
            throw new ConflictException(ErrorCode.PAYMENT_NOT_HOLDABLE,
                "Only a READY_TO_PAY payment can be held — this one is " + payment.getStatus());
        }
        payment.hold();
        paymentRepository.save(payment);
        paymentAuditService.record(payment.getId(), payment.getPurchaseOrderId(), PaymentAuditEventType.HELD,
            "Held by Finance. Reason: " + request.reason());
        return toResponse(payment);
    }

    /**
     * Release: {@code ON_HOLD → READY_TO_PAY}, then <strong>re-check the current
     * match verdict</strong> — releasing lands on the current state, not the
     * pre-hold one. Publishing {@link MatchInputChangedEvent} re-drives the match
     * (the established AFTER_COMMIT trigger), which may immediately re-block the
     * payment if the inputs disagree now. Audited.
     */
    @Transactional
    public PaymentResponse releaseHold(UUID paymentId, ReleaseHoldRequest request) {
        Payment payment = findOwnPayment(paymentId);
        if (payment.getStatus() != PaymentStatus.ON_HOLD) {
            throw new ConflictException(ErrorCode.PAYMENT_NOT_ON_HOLD,
                "Only an ON_HOLD payment can be released — this one is " + payment.getStatus());
        }
        payment.releaseHold();
        paymentRepository.save(payment);
        String reason = request != null && request.reason() != null && !request.reason().isBlank()
            ? request.reason() : "(none given)";
        paymentAuditService.record(payment.getId(), payment.getPurchaseOrderId(), PaymentAuditEventType.HOLD_RELEASED,
            "Hold released — re-checking against the current match verdict. Reason: " + reason);
        // Re-check: the AFTER_COMMIT match trigger re-evaluates and lands the current verdict (may re-block).
        eventPublisher.publishEvent(new MatchInputChangedEvent(payment.getPurchaseOrderId()));
        return toResponse(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(UUID paymentId) {
        return toResponse(findOwnPayment(paymentId));
    }

    private static String notReadyMessage(String action, Payment payment) {
        String base = "Only a READY_TO_PAY payment can be " + action + " — this one is " + payment.getStatus();
        if (payment.getStatus() == PaymentStatus.ON_HOLD) {
            return base + "; release the hold first (it was placed for a reason)";
        }
        if (payment.getStatus() == PaymentStatus.BLOCKED) {
            return base + "; a blocked payment is resolved through the discrepancy case, not paid straight through";
        }
        return base;
    }

    private Payment findOwnPayment(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new NotFoundException("Payment not found"));
        TenantGuard.assertOwned(payment);
        return payment;
    }

    private static PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
            payment.getId(),
            payment.getPurchaseOrderId(),
            payment.getType(),
            payment.getAmount(),
            payment.getStatus(),
            payment.getDueDate(),
            payment.getPaidDate(),
            payment.getPaymentReference(),
            payment.getMatchDetail());
    }
}
