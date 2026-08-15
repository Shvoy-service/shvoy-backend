package com.shvoy.shipments.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * A deliberate amendment of a provisional GRN's received quantities before
 * arrival confirmation (Story 7.4). A reason is required — this record gates
 * money movement, so a quantity change is never silent (old/new/who/why
 * audited).
 */
public record AmendGoodsReceiptRequest(
    @NotEmpty List<@Valid SkuQuantityRequest> lines,
    @NotBlank String reason
) {
}
