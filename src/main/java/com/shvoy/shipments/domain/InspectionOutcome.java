package com.shvoy.shipments.domain;

/**
 * A pre-shipment inspection's outcome (Story 7.4 revised) — three states, not
 * pass/fail, where the distinguishing factor is <em>timing relative to
 * shipment</em>:
 * <ul>
 *   <li>{@code PASS} — goods are good; the GRN creates clean.</li>
 *   <li>{@code REWORK_REQUIRED} — a <strong>pre-shipment hold</strong>: goods
 *       stayed at the factory, nothing shipped, so there is <em>nothing to
 *       receive</em>. This is <strong>not a discrepancy</strong> — it's a
 *       not-yet-shipped state; no GRN is created. The consignment holds until
 *       reworked goods are re-inspected and {@code PASS}.</li>
 *   <li>{@code FAIL} — a quality failure found once goods have shipped (or
 *       shipped anyway). The GRN <strong>still creates</strong>, flagged
 *       {@code qc_failed}: the goods are physically real and on the water, so
 *       blocking receipt would jam payment scheduling and stock over a quality
 *       dispute — that's a credit/dispute conversation, not a receipt blocker.</li>
 * </ul>
 * Confirmed Product Owner model, not a default to improve on.
 */
public enum InspectionOutcome {
    PASS,
    REWORK_REQUIRED,
    FAIL
}
