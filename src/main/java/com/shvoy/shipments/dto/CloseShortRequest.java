package com.shvoy.shipments.dto;

import jakarta.validation.constraints.NotBlank;

/** The Finance/Admin "close short" write-off reason (receipt rollup &amp; PO closure) — required, audited. */
public record CloseShortRequest(
    @NotBlank String reason
) {
}
