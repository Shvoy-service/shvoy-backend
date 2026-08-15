package com.shvoy.reconciliation.dto;

import java.util.List;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/**
 * The confirmed-PI leg of the three-way match (Story 6.5) — the Feature 5 →
 * Feature 6 read contract. Returned only for a PI that is genuinely confirmed
 * (active, {@code AUTO_CONFIRMED} or {@code APPROVED}); a PI still pending
 * approval or rejected is not a confirmed leg and the match cannot pass without
 * one. Carries the currency and per-SKU confirmed prices/quantities the match
 * compares; never exposes the {@code ProformaInvoice} entity or its status enum.
 */
@NamedInterface("reconciliation")
public record ConfirmedProformaInvoiceView(
    UUID proformaInvoiceId,
    UUID purchaseOrderId,
    String currency,
    List<ConfirmedProformaInvoiceLine> lines
) {
}
