package com.shvoy.payments.domain;

/**
 * The payment lifecycle state (Story 6.1) — the states are declared here from
 * the start so the column never needs widening; the actual transitions get
 * enforced and threaded in 6.8, and the match/blocking logic that drives them
 * is 6.5's.
 *
 * <ul>
 *   <li>{@code PENDING} — the obligation exists but isn't yet payable.</li>
 *   <li>{@code BLOCKED} — a three-way-match discrepancy is holding it (6.5).</li>
 *   <li>{@code READY_TO_PAY} — the match passed; it may be released.</li>
 *   <li>{@code PAID} — released. (SHVOY records the Pay decision; it never moves
 *       money — actual settlement is outside the product, per the wireframes.)</li>
 *   <li>{@code ON_HOLD} — a user parked it (the Screen 6 "Hold" action).</li>
 * </ul>
 */
public enum PaymentStatus {
    PENDING,
    BLOCKED,
    READY_TO_PAY,
    PAID,
    ON_HOLD;

    /**
     * The full payment lifecycle, in one place (Story 6.8) — the 5.7 pattern.
     * Every legitimate move is here; anything else ({@code PENDING → PAID},
     * {@code BLOCKED → PAID}, {@code ON_HOLD → PAID}, {@code PAID → anything})
     * is rejected with {@code INVALID_STATUS_TRANSITION} rather than corrupting
     * state. The narrow "which buttons exist when" map the frontend renders off
     * status is a subset of this: the human actions (pay/hold/release) also
     * enforce their own preconditions with distinct codes at the service layer,
     * so this guard is defense-in-depth. The extra system moves permitted here
     * ({@code READY_TO_PAY → PENDING/BLOCKED}, {@code BLOCKED → PENDING}) are the
     * match re-evaluating as inputs change (a leg vanishing → awaiting; a leg
     * disagreeing → blocked) and {@code ON_HOLD → BLOCKED} is a re-match failing
     * while the payment was held. A same-state move is an idempotent no-op (so a
     * deterministic re-evaluation can't trip the guard).
     *
     * <ul>
     *   <li>{@code PENDING → READY_TO_PAY | BLOCKED} (match / deposit gate)</li>
     *   <li>{@code BLOCKED → READY_TO_PAY} (6.6 resolution/override) {@code | PENDING} (a leg vanished)</li>
     *   <li>{@code READY_TO_PAY → PAID | ON_HOLD} (6.8) {@code | BLOCKED | PENDING} (match re-eval)</li>
     *   <li>{@code ON_HOLD → READY_TO_PAY} (release) {@code | BLOCKED} (re-match failed while held) {@code | PENDING} (release re-check, a leg vanished)</li>
     *   <li>{@code PAID} — terminal.</li>
     * </ul>
     */
    public boolean canTransitionTo(PaymentStatus target) {
        if (this == target) {
            return true;
        }
        return switch (this) {
            case PENDING -> target == READY_TO_PAY || target == BLOCKED;
            case BLOCKED -> target == READY_TO_PAY || target == PENDING;
            case READY_TO_PAY -> target == PAID || target == ON_HOLD || target == BLOCKED || target == PENDING;
            case ON_HOLD -> target == READY_TO_PAY || target == BLOCKED || target == PENDING;
            case PAID -> false;
        };
    }
}
