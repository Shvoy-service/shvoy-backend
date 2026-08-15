package com.shvoy.payments.domain;

/**
 * What a match verdict <em>did</em> (Story 6.5 re-spec) — the consequence
 * dispatch on {@code terms_type}, recorded on each result so the outcome is
 * self-describing.
 * <ul>
 *   <li>{@code PAYMENT_GATED} — deposit/balance &amp; zero-deposit terms: the
 *       verdict drives a per-PO payment transition (READY_TO_PAY / BLOCKED).</li>
 *   <li>{@code STATEMENT_RECORDED} — rolling terms: the verdict is recorded (and
 *       a discrepancy case opens on failure) but <strong>no</strong> per-PO
 *       payment transition happens; it feeds the statement view.</li>
 * </ul>
 */
public enum MatchPolicy {
    PAYMENT_GATED,
    STATEMENT_RECORDED
}
