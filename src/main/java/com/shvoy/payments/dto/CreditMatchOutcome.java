package com.shvoy.payments.dto;

/**
 * Why an invoice's claimed credit did or didn't match an open ledger entry
 * (Story 6.7's match-check):
 * <ul>
 *   <li>{@code MATCHED} — an OPEN entry for the same PO with the exact same
 *       amount.</li>
 *   <li>{@code AMOUNT_MISMATCH} — an OPEN entry exists for the PO, but no
 *       amount matches the claim exactly (credits are agreed figures, so there
 *       is no tolerance).</li>
 *   <li>{@code NO_OPEN_CREDIT} — no OPEN entry for that PO at all (wrong PO, no
 *       credit logged, or the only entry is already APPLIED/CANCELLED).</li>
 * </ul>
 */
public enum CreditMatchOutcome {
    MATCHED,
    AMOUNT_MISMATCH,
    NO_OPEN_CREDIT
}
