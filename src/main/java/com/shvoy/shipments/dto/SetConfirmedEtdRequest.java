package com.shvoy.shipments.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Set or revise the supplier's confirmed ETD (Story 7.5). The reason is optional
 * — demanding one for every routine slip is friction, but the field lets a
 * meaningful one be recorded.
 */
public record SetConfirmedEtdRequest(
    @NotNull LocalDate confirmedEtd,
    @Size(max = 500) String reason
) {
}
