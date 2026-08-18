package com.shvoy.payments.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.payments.dto.HoldPaymentRequest;
import com.shvoy.payments.dto.PayPaymentRequest;
import com.shvoy.payments.dto.PaymentResponse;
import com.shvoy.payments.dto.ReleaseHoldRequest;
import com.shvoy.payments.service.PaymentReleaseService;

/**
 * Story 6.8 — the Pay / Hold / Release actions on a payment (Screen 6). The
 * human decision at the end of the pipeline.
 *
 * <p><strong>{@code FINANCE}/{@code ADMIN} only</strong> — {@code PURCHASING}
 * explicitly cannot: the segregation that runs through 5.5 and 6.6, the role that
 * creates obligations doesn't release them. There is deliberately <strong>no Pay
 * or Hold path on a {@code BLOCKED} payment</strong> — its only exits are 6.6's
 * resolution paths (fix / credit / override / dispute); this controller adds
 * nothing there.
 */
@RestController
class PaymentReleaseController {

    private final PaymentReleaseService paymentReleaseService;

    PaymentReleaseController(PaymentReleaseService paymentReleaseService) {
        this.paymentReleaseService = paymentReleaseService;
    }

    /** Pay: READY_TO_PAY → PAID (terminal). Optional reference + overridable paid date; body itself optional. */
    @PostMapping("/api/payments/{id}/pay")
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
    PaymentResponse pay(@PathVariable UUID id,
            @Valid @RequestBody(required = false) PayPaymentRequest request) {
        return paymentReleaseService.pay(id, request == null ? new PayPaymentRequest(null, null) : request);
    }

    /** Hold: READY_TO_PAY → ON_HOLD, mandatory reason. */
    @PostMapping("/api/payments/{id}/hold")
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
    PaymentResponse hold(@PathVariable UUID id, @Valid @RequestBody HoldPaymentRequest request) {
        return paymentReleaseService.hold(id, request);
    }

    /** Release hold: ON_HOLD → READY_TO_PAY, then re-checked against the current match verdict. Reason optional. */
    @PostMapping("/api/payments/{id}/release-hold")
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
    PaymentResponse releaseHold(@PathVariable UUID id,
            @RequestBody(required = false) ReleaseHoldRequest request) {
        return paymentReleaseService.releaseHold(id, request);
    }
}
