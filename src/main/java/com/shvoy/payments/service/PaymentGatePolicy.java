package com.shvoy.payments.service;

import org.springframework.stereotype.Component;

import com.shvoy.payments.domain.PaymentType;

/**
 * The per-payment-type gate policy (Story 6.5) — the <strong>one</strong>
 * isolated place that decides whether a payment is gated by the three-way
 * match, so the flagged deposit-gating question is a one-line change.
 *
 * <p>The business rule says "a payment" cannot be marked ready-to-pay without a
 * passing match, but a literal reading deadlocks deposits: they're typically
 * due long before a PI is confirmed, goods are received, or an invoice is
 * logged, so gating them on a full match would make every order's deposit
 * unpayable. So the built interpretation — <strong>flagged for the Product
 * Owners</strong> — is: the match gates the <strong>balance</strong>; the
 * <strong>deposit</strong> is payable per its terms without it (its gate is
 * effectively PO generation). If the POs want deposits gated too, this method
 * is the single line that changes.
 */
@Component
class PaymentGatePolicy {

    boolean requiresThreeWayMatch(PaymentType type) {
        return type == PaymentType.BALANCE;
    }
}
