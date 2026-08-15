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
    ON_HOLD
}
