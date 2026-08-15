package com.shvoy.payments.domain;

/**
 * What an invoice explicitly declares it covers (invoice remodel, confirmed PO
 * answer) — mandatory on every invoice, with type-dependent references validated
 * for coherence at entry (existence/ownership only; amount-vs-coverage is the
 * match's job).
 * <ul>
 *   <li>{@code DEPOSIT} / {@code BALANCE} — the deposit / the remainder of a
 *       deposit-balance PO.</li>
 *   <li>{@code SHIPMENT} — tied to one receipted consignment/GRN (the strongest
 *       reconciliation signal).</li>
 *   <li>{@code LINES} — specific PO lines / partial quantities.</li>
 *   <li>{@code AMOUNT} — free-standing, no breakdown; the <strong>fallback
 *       only</strong>, the weakest reconciliation signal (accepted but flagged,
 *       never the default path).</li>
 * </ul>
 */
public enum InvoiceCoversType {
    DEPOSIT,
    BALANCE,
    SHIPMENT,
    LINES,
    AMOUNT
}
