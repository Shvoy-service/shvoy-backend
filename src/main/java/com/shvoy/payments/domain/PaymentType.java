package com.shvoy.payments.domain;

/**
 * Whether a payment is the up-front {@code DEPOSIT} or the {@code BALANCE} owed
 * on a PO (Story 6.1) — the Screen 1/6 "Type" column. Stored as a string. A PO
 * with a deposit % &gt; 0 produces one of each; a 0%-deposit PO produces only a
 * {@code BALANCE} for the full amount.
 */
public enum PaymentType {
    DEPOSIT,
    BALANCE
}
