package com.shvoy.containerfill.dto;

import jakarta.validation.constraints.Size;

/** Declines a container-fill offer — ship without (Story 8.3). Reason optional, kept in the audit trail. */
public record DeclineContainerFillOfferRequest(@Size(max = 500) String reason) {
}
