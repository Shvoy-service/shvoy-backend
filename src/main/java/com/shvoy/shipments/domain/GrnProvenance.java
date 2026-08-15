package com.shvoy.shipments.domain;

/**
 * How a provisional GRN came to be, carried on the receipt (Story 7.4 revised):
 * <ul>
 *   <li>{@code CLEAN} — created after an inspection that was due and passed.</li>
 *   <li>{@code INSPECTION_NOT_DUE} — created legitimately without an inspection
 *       (the shipment wasn't inspection-due; the no-QC-service path).</li>
 *   <li>{@code QC_FAILED} — created despite a failed inspection: the paper
 *       matches the order even though quality doesn't. The match (6.5) is
 *       <strong>not</strong> blocked by this; the quality conversation runs
 *       alongside payment control.</li>
 * </ul>
 */
public enum GrnProvenance {
    CLEAN,
    INSPECTION_NOT_DUE,
    QC_FAILED
}
