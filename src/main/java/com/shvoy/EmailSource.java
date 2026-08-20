package com.shvoy;

/**
 * Which business flow a {@link EmailMessage} came from (Story 9.4) — carried on
 * the message so the send record can say <em>which</em> send failed ("the invite
 * to X", "the PO to Y"), without the transport having to guess. Metadata, not
 * coupling: it enriches the message the consumer already builds; the {@code
 * send} contract and the consumers' fire-and-forget behaviour are unchanged.
 */
public enum EmailSource {
    INVITATION,
    PURCHASE_ORDER,
    APPROVAL_REQUEST,
    DISCREPANCY,
    CONTAINER_FILL_REMINDER,
    CONTAINER_FILL_LAPSED,
    OTHER
}
