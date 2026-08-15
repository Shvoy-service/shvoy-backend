package com.shvoy.payments.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.shvoy.payments.domain.DiscrepancyResolutionType;
import com.shvoy.payments.domain.DiscrepancyStatus;

/**
 * The resolver's side-by-side (Story 6.6) — Screen 6's expandable mismatch row,
 * served whole so the client assembles nothing. Every leg's current value for
 * each check, the failure detail 6.5 recorded, the claimed credit's ledger
 * verdict, and the PO's open ledger entries that may explain the variance.
 */
public record DiscrepancyViewResponse(
    UUID caseId,
    UUID paymentId,
    UUID purchaseOrderId,
    String poNumber,
    DiscrepancyStatus status,
    DiscrepancyResolutionType resolutionType,
    String failureDetail,
    UUID claimedBy,
    Instant claimedAt,
    UUID resolvedBy,
    Instant resolvedAt,
    String resolutionReason,
    UUID creditLedgerEntryId,
    List<LegLine> poLines,
    List<LegLine> piLines,
    List<GrnLegLine> grnLines,
    InvoiceLeg invoice,
    String claimedCreditVerdict,
    List<CreditLedgerEntryResponse> openLedgerEntries,
    Instant createdAt,
    Instant updatedAt
) {

    /** A PO or PI line as the comparison shows it — SKU, quantity, unit price. */
    public record LegLine(UUID skuId, int quantity, BigDecimal unitPriceAmount) {
    }

    /** A GRN received line. */
    public record GrnLegLine(UUID skuId, int receivedQuantity) {
    }

    /** The invoice leg — its amount and what it claims to have deducted. */
    public record InvoiceLeg(BigDecimal amount, String currency, BigDecimal claimedCreditAmount,
            String claimedCreditReference) {
    }
}
