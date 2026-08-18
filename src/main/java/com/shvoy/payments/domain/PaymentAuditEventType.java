package com.shvoy.payments.domain;

/**
 * The kinds of entry on a payment's immutable audit trail (Story 6.2, focused
 * on due-date derivation for now):
 *
 * <ul>
 *   <li>{@code DUE_DATE_SET} — a due date was derived and set for the first
 *       time (a deposit at generation, or a balance when its anchor date
 *       became known).</li>
 *   <li>{@code DUE_DATE_RECALCULATED} — a revised anchor date moved an
 *       already-set due date; the detail records old → new and why.</li>
 * </ul>
 */
public enum PaymentAuditEventType {
    DUE_DATE_SET,
    DUE_DATE_RECALCULATED,
    /** The three-way match passed — the balance became READY_TO_PAY (Story 6.5). */
    MATCH_PASSED,
    /** The three-way match failed — the balance was BLOCKED; the detail records which leg (Story 6.5). */
    MATCH_BLOCKED,
    /** A deposit was made payable without the match, per the per-type gate policy (Story 6.5). */
    DEPOSIT_PAYABLE,
    /** Finance recorded the payment as paid — READY_TO_PAY → PAID, terminal (Story 6.8). */
    PAID,
    /** Finance held a clean payment — READY_TO_PAY → ON_HOLD, with a mandatory reason (Story 6.8). */
    HELD,
    /** A hold was released — ON_HOLD → READY_TO_PAY, then re-checked against the current verdict (Story 6.8). */
    HOLD_RELEASED
}
