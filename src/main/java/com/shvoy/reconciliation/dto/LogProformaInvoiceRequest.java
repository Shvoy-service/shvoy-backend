package com.shvoy.reconciliation.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

/**
 * Records a supplier's confirmed proforma invoice against a PO — see Story
 * 5.2. The same shape both the manual-entry endpoint and (later, per the
 * roadmap's AI Document Intelligence layer) a programmatic extraction feed
 * would construct — see {@code ProformaInvoiceService#log}'s Javadoc.
 *
 * {@code currency} is only pattern-checked here (3 uppercase letters); a
 * real ISO 4217 check happens in the service, same two-layer validation as
 * {@code SkuPriceRequest}/{@code SkuService#buildUnitPrice} — recorded as
 * stated even when it differs from the PO's, not blocked here.
 */
public record LogProformaInvoiceRequest(
    @NotBlank String piReference,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 currency code") String currency,
    @NotEmpty @Valid List<ProformaInvoiceLineRequest> lines
) {
}
