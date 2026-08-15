package com.shvoy.payments.service;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.shvoy.suppliers.domain.PaymentTermsType;

/**
 * The <strong>one</strong> isolated place that decides what a match verdict
 * <em>does</em> (Story 6.5 re-spec) — the consequence dispatch on {@code
 * terms_type}, kept deliberately apart from the strategy dispatch on {@code
 * covers_type} (what "matches" means). The two compose freely: a rolling
 * supplier's shipment invoice uses the same strategy as a deposit/balance
 * supplier's; only this policy differs.
 *
 * <ul>
 *   <li>{@code DEPOSIT_BALANCE} / {@code ZERO_DEPOSIT} (and an unconfigured
 *       supplier — block-by-default) — the verdict <strong>gates the payment</strong>:
 *       a pass flips it {@code READY_TO_PAY}, a fail {@code BLOCKED} + a
 *       discrepancy case.</li>
 *   <li>{@code ROLLING} — the match runs identically as a <strong>document
 *       control</strong>: verdicts are recorded and discrepancy cases open on
 *       failure, but there is <strong>no per-PO payment transition</strong>; the
 *       statement view consumes it. (Replaces the old per-<em>type</em> gate.)</li>
 * </ul>
 */
@Component
class MatchConsequencePolicy {

    /** True when this supplier's terms drive per-PO payment gating; false for rolling (record-only). */
    boolean gatesPayments(Optional<PaymentTermsType> termsType) {
        return termsType.map(type -> type != PaymentTermsType.ROLLING).orElse(true);
    }
}
