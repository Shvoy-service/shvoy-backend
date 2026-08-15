package com.shvoy.payments.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Records a supplier's final invoice against a PO — Story 6.4. The same shape
 * the manual endpoint and (later) a programmatic AI-extraction feed construct,
 * converging on one internal operation (see {@code InvoiceService#log}).
 *
 * Validation is <strong>well-formedness only</strong> (the "record faithfully,
 * judge later" posture from 5.2): a non-blank reference, a positive 2dp amount,
 * a real ISO 4217 currency (pattern-checked here, ISO-validated in the
 * service), and a date. Disagreement with the PO/PI amount — or a differing
 * currency — is recorded, never rejected; the match (6.5) judges it.
 *
 * {@code coversType} is mandatory (invoice remodel); its type-dependent
 * references are validated for existence/ownership only, not for whether the
 * amount agrees with the coverage (the match judges that).
 *
 * {@code claimedCredit*} is optional — what the supplier says they've deducted;
 * captured as-stated, validated against the ledger only at match time (6.7's
 * rule in 6.5).
 */
public record LogInvoiceRequest(
    @NotBlank String invoiceReference,
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal amount,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 currency code") String currency,
    @NotNull LocalDate invoiceDate,
    @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal claimedCreditAmount,
    String claimedCreditReference,
    // Mandatory (invoice remodel): what this invoice declares it covers. Type-dependent
    // references below are validated for coherence at entry (InvoiceCoverageValidator).
    @NotNull com.shvoy.payments.domain.InvoiceCoversType coversType,
    // Required for coversType == SHIPMENT: the receipted consignment this invoice ties to.
    UUID coversConsignmentId,
    // Required for coversType == LINES: the PO lines (by SKU + claimed quantity) claimed.
    @Valid List<InvoiceCoveredLineRequest> coveredLines
) {
}
