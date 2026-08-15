package com.shvoy.shipments.domain;

/**
 * The receipt lifecycle of a single {@link ShipmentConsignment} — a PO's
 * portion of a shipment, not the shipment as a whole (the co-loading rule
 * means each portion is receipted independently).
 *
 * <p>The progression:
 * <ul>
 *   <li>{@code DOCUMENTS_PENDING} — every consignment's starting state: the
 *       BL-level record exists but this portion's documents (its own packing
 *       list, inspection report) aren't yet enough to receipt it.</li>
 *   <li>{@code REWORK_REQUIRED} — a pre-shipment hold (Story 7.4 revised): an
 *       inspection required rework, the goods stayed at the factory, nothing
 *       shipped. <strong>Not a discrepancy</strong> — there is nothing to
 *       receive; GRN creation is hard-blocked until a re-inspection passes and
 *       releases the hold back to {@code DOCUMENTS_PENDING}.</li>
 *   <li>{@code PROVISIONALLY_RECEIPTED} — the provisional GRN has been created
 *       from the documents (Story 7.4); this is the record the three-way match
 *       (6.5) runs against, and it explicitly does <em>not</em> require physical
 *       arrival.</li>
 *   <li>{@code ARRIVED_CONFIRMED} — physical arrival confirmed against the
 *       provisional GRN with no shortfall (Story 7.6).</li>
 *   <li>{@code ARRIVED_WITH_DISCREPANCY} — arrival confirmed but it didn't match
 *       the provisional GRN; the mismatch spawns a discrepancy record for the
 *       6.7 ledger — a credit conversation, never a reopened payment (Story
 *       7.6).</li>
 * </ul>
 *
 * <p>The states are defined now (Story 7.1, model first) so the schema and
 * entity are stable; the <em>transitions</em> between them are enforced by the
 * later stories that own each step (7.4, 7.6). Stored as a string, per the
 * codebase convention for enums.
 */
public enum ReceiptStatus {
    DOCUMENTS_PENDING,
    REWORK_REQUIRED,
    PROVISIONALLY_RECEIPTED,
    ARRIVED_CONFIRMED,
    ARRIVED_WITH_DISCREPANCY
}
