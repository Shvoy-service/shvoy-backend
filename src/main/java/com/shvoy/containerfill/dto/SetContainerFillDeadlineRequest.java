package com.shvoy.containerfill.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Sets (or revises) a container-fill offer's decision deadline (Story 8.2).
 *
 * <p>The wire value is the {@code Instant} itself (ISO-8601 UTC) — the frontend
 * converts the London-entered time before sending, so the backend never parses an
 * ambiguous local time (the DST rule; see docs/CONTRACT.md "Dates and timestamps").
 * The deadline is displayed in Europe/London everywhere. Optional {@code reason}
 * captures why a deadline was renegotiated, for the audit trail.
 */
public record SetContainerFillDeadlineRequest(
    @NotNull Instant deadline,
    @Size(max = 500) String reason
) {
}
