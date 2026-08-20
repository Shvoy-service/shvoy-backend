package com.shvoy.containerfill.dto;

import jakarta.validation.constraints.NotBlank;

/** Cancels an undecided offer with a required reason (the cancel-and-relog correction path, Story 8.1). */
public record CancelContainerFillOfferRequest(@NotBlank String reason) {
}
