package com.shvoy.containerfill.domain;

/**
 * The lifecycle of a {@link ContainerFillOffer} (Story 8.1, model first).
 *
 * <ul>
 *   <li>{@code OPEN} — flagged, no decision deadline yet (all 8.1 sets on create).</li>
 *   <li>{@code AWAITING_DECISION} — a deadline has been set (8.2's transition).</li>
 *   <li>{@code CONFIRMED} — decided: fill it (8.3).</li>
 *   <li>{@code DECLINED} — decided: ship without (8.3).</li>
 *   <li>{@code LAPSED} — the deadline passed undecided (8.3 resolves this to
 *       ship-without, but the distinct state records that it lapsed rather than
 *       was declined — the difference matters for the supplier-relationship picture).</li>
 *   <li>{@code CANCELLED} — withdrawn before a decision; the correction path
 *       (cancel-and-relog), so offer figures never mutate silently once a
 *       deadline clock may be running against them.</li>
 * </ul>
 *
 * <p>The states are defined now so the schema is stable; only {@code OPEN} and
 * {@code CANCELLED} are set by 8.1 — 8.2/8.3 own the remaining transitions.
 */
public enum ContainerFillOfferStatus {
    OPEN,
    AWAITING_DECISION,
    CONFIRMED,
    DECLINED,
    LAPSED,
    CANCELLED
}
