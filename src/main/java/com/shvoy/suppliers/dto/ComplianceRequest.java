package com.shvoy.suppliers.dto;

import jakarta.validation.constraints.NotNull;

import com.shvoy.suppliers.domain.ComplianceStatus;

/** Set a supplier's compliance status (supplier remodel) — a simple MVP flag. */
public record ComplianceRequest(
    @NotNull ComplianceStatus status
) {
}
