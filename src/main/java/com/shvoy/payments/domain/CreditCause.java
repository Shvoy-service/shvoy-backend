package com.shvoy.payments.domain;

/**
 * Why a credit exists on the ledger (Story 6.7) — the cause paired with a
 * free-text detail (required for {@code OTHER}). {@code QUALITY_FAILURE} and
 * the entry's nullable NCR reference are a <strong>seam</strong> for Roadmap
 * v2's NCR-caused credits (a quality failure links to its source NCR) — the
 * value exists so the model doesn't need widening later, but no NCR
 * functionality is built here, pending the phasing decision.
 */
public enum CreditCause {
    SHORT_SHIPMENT,
    DAMAGE,
    AGREED_PRICE_CORRECTION,
    QUALITY_FAILURE,
    OTHER
}
