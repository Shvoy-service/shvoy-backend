package com.shvoy.reconciliation.domain;

/**
 * The kinds of event recorded on a PI's immutable reconciliation audit trail
 * (Story 5.7) — one entry per significant thing that happened, so "what was
 * compared, what the variance was, who decided what, when, and why" reads as a
 * coherent chronology rather than being scattered across four stories' tables.
 *
 * <ul>
 *   <li>{@code PI_LOGGED} — the PI was recorded (5.2).</li>
 *   <li>{@code COMPARISON_RECORDED} — the three-way comparison ran (5.3).</li>
 *   <li>{@code AUTO_CONFIRMED} / {@code ROUTED_FOR_APPROVAL} — the tolerance
 *       evaluation outcome (5.4); the detail carries the variance and the
 *       tolerance in force at the time.</li>
 *   <li>{@code APPROVAL_RECORDED} — a single sign-off that didn't yet meet the
 *       threshold (5.5).</li>
 *   <li>{@code APPROVED} / {@code REJECTED} — the terminal decision (5.5).</li>
 *   <li>{@code SUPERSEDED} — a corrected PI replaced this one (5.1/5.7).</li>
 * </ul>
 */
public enum ReconciliationAuditEventType {
    PI_LOGGED,
    COMPARISON_RECORDED,
    AUTO_CONFIRMED,
    ROUTED_FOR_APPROVAL,
    APPROVAL_RECORDED,
    APPROVED,
    REJECTED,
    SUPERSEDED
}
