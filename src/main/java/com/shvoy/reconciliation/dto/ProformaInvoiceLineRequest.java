package com.shvoy.reconciliation.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * One line of a {@link LogProformaInvoiceRequest} — see Story 5.2. Bean
 * validation here enforces well-formedness only (a real SKU reference, a
 * positive 4dp-max price, a positive quantity); it deliberately does not
 * (and cannot, at this layer) check agreement with the PO — that's
 * reconciliation's job, not entry validation. See {@code
 * ProformaInvoiceService}'s Javadoc for the "record faithfully, judge
 * later" principle this boundary exists to protect.
 */
public record ProformaInvoiceLineRequest(
    @NotNull UUID skuId,
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal confirmedUnitPriceAmount,
    @Positive int confirmedQuantity
) {
}
