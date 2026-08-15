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
    DUE_DATE_RECALCULATED
}
