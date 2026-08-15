package com.shvoy.payments.dto;

import java.util.UUID;

/**
 * The answer from the ledger's match-check (Story 6.7) — the reusable operation
 * 6.5 calls at match time. {@code matched} is the headline; {@code
 * matchedEntryId} is the OPEN entry to apply on a match (null otherwise); {@code
 * outcome} explains a non-match. This operation only <em>answers</em> — applying
 * the matched entry ({@code OPEN → APPLIED}) is a separate call, so a read-only
 * check never mutates the ledger.
 */
public record CreditMatchResult(
    boolean matched,
    UUID matchedEntryId,
    CreditMatchOutcome outcome
) {
}
