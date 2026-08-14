package com.shvoy.reconciliation.domain;

/**
 * What a single {@link ReconciliationLine} represents — see Story 5.3's
 * line-correlation scope (item 5). Correlation is by SKU (the 5.1 decision),
 * so the messy cases where a PI and its PO don't line up cleanly are
 * findings this story must surface rather than silently drop:
 *
 * <ul>
 *   <li>{@code MATCHED} — one PI line and one PO line share a SKU; this is
 *       the only type that carries a computed variance.</li>
 *   <li>{@code UNMATCHED_PI_LINE} — the supplier's PI has a line for a SKU
 *       that isn't on the PO (they added something).</li>
 *   <li>{@code UNMATCHED_PO_LINE} — the PO has a line for a SKU the PI
 *       doesn't mention (they omitted something).</li>
 *   <li>{@code DUPLICATE_SKU} — the same SKU appears more than once on one
 *       side; pairing can't be assumed, so it's flagged rather than guessed.</li>
 * </ul>
 *
 * The non-{@code MATCHED} types are structural findings, not variance
 * calculations — 5.4 routes on them and Screen 4 shows them; this story only
 * detects and records them.
 */
public enum ReconciliationFindingType {
    MATCHED,
    UNMATCHED_PI_LINE,
    UNMATCHED_PO_LINE,
    DUPLICATE_SKU
}
