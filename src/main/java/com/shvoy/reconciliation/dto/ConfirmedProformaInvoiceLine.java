package com.shvoy.reconciliation.dto;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/**
 * One line of the confirmed PI, as the three-way match (Story 6.5, in {@code
 * payments}) needs it: the SKU, the supplier-confirmed 4dp unit price, and the
 * confirmed quantity — the accepted commercial position the invoice is checked
 * against. Deliberately narrow, same minimal-cross-module-contract reasoning as
 * {@code PurchaseOrderReconciliationLine}.
 */
@NamedInterface("reconciliation")
public record ConfirmedProformaInvoiceLine(
    UUID skuId,
    BigDecimal confirmedUnitPriceAmount,
    int confirmedQuantity
) {
}
