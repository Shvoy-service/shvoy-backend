package com.shvoy.reconciliation.domain;

import java.util.Set;

/**
 * The reconciliation lifecycle — declared from Story 5.1, made a coherent,
 * transition-guarded state machine in 5.7 ({@link #canTransitionTo}). Each
 * state and who moves the PI into it:
 *
 * <ul>
 *   <li>{@code LOGGED} — a PI has been recorded (5.2); comparison not yet run.</li>
 *   <li>{@code AUTO_CONFIRMED} — within tolerance, system-confirmed (5.4).</li>
 *   <li>{@code ROUTED_FOR_APPROVAL} — routed to a human, awaiting decision
 *       (5.4); for price increases, where the 2-of-N sign-offs accumulate
 *       (5.5). This <strong>is</strong> the state the story calls
 *       "PENDING_APPROVAL" — kept under the name 5.4/5.5 already persist and
 *       the frontend already binds to, rather than renaming a live enum value
 *       across the DB, tests, and the cross-repo money/tolerance contract; the
 *       lifecycle coherence 5.7 adds comes from the guarded transitions below,
 *       not the label.</li>
 *   <li>{@code APPROVED} — the single approver, or the required N, confirmed it (5.5).</li>
 *   <li>{@code REJECTED} — an approver rejected it (5.5).</li>
 *   <li>{@code SUPERSEDED} — a corrected PI replaced this one (5.1's cardinality,
 *       5.7); a terminal state so a replaced PI never sits misleadingly in an
 *       active-looking status.</li>
 * </ul>
 *
 * The permitted transitions are defined here, in one place, so an invalid one
 * (an {@code AUTO_CONFIRMED} PI becoming {@code ROUTED_FOR_APPROVAL}, a
 * {@code REJECTED} one silently becoming {@code APPROVED}) is rejected rather
 * than corrupting state. Supersession can happen from any non-terminal-by-
 * replacement state, since a supplier can re-issue at any point.
 */
public enum ProformaInvoiceStatus {
    LOGGED,
    AUTO_CONFIRMED,
    ROUTED_FOR_APPROVAL,
    APPROVED,
    REJECTED,
    SUPERSEDED;

    /**
     * Whether the PI may move from this state to {@code target}. A same-state
     * transition is permitted as an idempotent no-op (so a deterministic
     * re-evaluation can't trip the guard); every other move must be in the
     * table below. Any state except {@code SUPERSEDED} itself can be
     * superseded.
     */
    public boolean canTransitionTo(ProformaInvoiceStatus target) {
        if (this == target) {
            return true;
        }
        if (target == SUPERSEDED) {
            return this != SUPERSEDED;
        }
        return switch (this) {
            case LOGGED -> Set.of(AUTO_CONFIRMED, ROUTED_FOR_APPROVAL).contains(target);
            case ROUTED_FOR_APPROVAL -> Set.of(APPROVED, REJECTED).contains(target);
            case AUTO_CONFIRMED, APPROVED, REJECTED, SUPERSEDED -> false;
        };
    }
}
