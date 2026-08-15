package com.shvoy.payments.domain;

/**
 * How a discrepancy case was resolved (Story 6.6):
 * <ul>
 *   <li>{@code CORRECTED} — the underlying data was fixed (invoice superseded,
 *       GRN amended) and the match then passed; auto-resolved.</li>
 *   <li>{@code CREDITED} — a credit was logged from the case (path b) and the
 *       match passed once a claiming/reduced invoice aligned.</li>
 *   <li>{@code OVERRIDDEN} — a resolver accepted the difference as-is (path c),
 *       force-passing the payment with a required reason.</li>
 * </ul>
 */
public enum DiscrepancyResolutionType {
    CORRECTED,
    CREDITED,
    OVERRIDDEN
}
