package com.shvoy.payments.domain;

/**
 * A credit ledger entry's lifecycle (Story 6.7):
 * <ul>
 *   <li>{@code OPEN} — agreed, not yet applied against an invoice.</li>
 *   <li>{@code APPLIED} — matched against an invoice that claimed it (terminal;
 *       an entry applies exactly once).</li>
 *   <li>{@code CANCELLED} — closed with a reason, will never apply (terminal).</li>
 * </ul>
 * Apply and cancel are only valid from {@code OPEN} — enforced by the entity,
 * so an applied credit can never be re-applied and a cancelled one never
 * re-opened.
 */
public enum CreditLedgerStatus {
    OPEN,
    APPLIED,
    CANCELLED
}
