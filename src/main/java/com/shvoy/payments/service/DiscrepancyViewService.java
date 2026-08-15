package com.shvoy.payments.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.Money;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.payments.domain.CreditLedgerStatus;
import com.shvoy.payments.domain.DiscrepancyCase;
import com.shvoy.payments.domain.DiscrepancyStatus;
import com.shvoy.payments.domain.Invoice;
import com.shvoy.payments.dto.CreditLedgerEntryResponse;
import com.shvoy.payments.dto.DiscrepancyCaseSummary;
import com.shvoy.payments.dto.DiscrepancyStatsResponse;
import com.shvoy.payments.dto.DiscrepancyViewResponse;
import com.shvoy.payments.dto.DiscrepancyViewResponse.GrnLegLine;
import com.shvoy.payments.dto.DiscrepancyViewResponse.InvoiceLeg;
import com.shvoy.payments.dto.DiscrepancyViewResponse.LegLine;
import com.shvoy.payments.repository.DiscrepancyCaseRepository;
import com.shvoy.payments.repository.GrnProjectionLineRepository;
import com.shvoy.payments.repository.InvoiceRepository;
import com.shvoy.purchaseorders.dto.PurchaseOrderReconciliationLine;
import com.shvoy.purchaseorders.service.PurchaseOrderService;
import com.shvoy.reconciliation.dto.ConfirmedProformaInvoiceView;
import com.shvoy.reconciliation.service.ProformaInvoiceMatchService;

/**
 * The read side of discrepancy resolution (Story 6.6): the queue, the dashboard
 * stat, and the side-by-side. The side-by-side is <strong>served, not
 * assembled by the client</strong> — every leg's current value, the failure
 * detail 6.5 recorded, the claimed credit's ledger verdict, and the PO's open
 * ledger entries in one shape. It reads the legs fresh (the resolver wants the
 * <em>current</em> comparison), never re-running the match verdict.
 */
@Service
public class DiscrepancyViewService {

    private final DiscrepancyCaseRepository caseRepository;
    private final PurchaseOrderService purchaseOrderService;
    private final ProformaInvoiceMatchService proformaInvoiceMatchService;
    private final GrnProjectionLineRepository grnProjectionLineRepository;
    private final InvoiceRepository invoiceRepository;
    private final CreditLedgerService creditLedgerService;

    DiscrepancyViewService(DiscrepancyCaseRepository caseRepository, PurchaseOrderService purchaseOrderService,
            ProformaInvoiceMatchService proformaInvoiceMatchService,
            GrnProjectionLineRepository grnProjectionLineRepository, InvoiceRepository invoiceRepository,
            CreditLedgerService creditLedgerService) {
        this.caseRepository = caseRepository;
        this.purchaseOrderService = purchaseOrderService;
        this.proformaInvoiceMatchService = proformaInvoiceMatchService;
        this.grnProjectionLineRepository = grnProjectionLineRepository;
        this.invoiceRepository = invoiceRepository;
        this.creditLedgerService = creditLedgerService;
    }

    /** The claimable queue; {@code status == null} lists all. Newest first. */
    @Transactional(readOnly = true)
    public List<DiscrepancyCaseSummary> list(DiscrepancyStatus status) {
        return caseRepository.findAll().stream()
            .filter(caseEntity -> status == null || caseEntity.getStatus() == status)
            .sorted(Comparator.comparing(DiscrepancyCase::getCreatedAt).reversed())
            .map(caseEntity -> new DiscrepancyCaseSummary(
                caseEntity.getId(), caseEntity.getPaymentId(), caseEntity.getPurchaseOrderId(),
                purchaseOrderService.getSummary(caseEntity.getPurchaseOrderId()).poNumber(),
                caseEntity.getStatus(), caseEntity.getResolutionType(), caseEntity.getFailureDetail(),
                caseEntity.getClaimedBy(), caseEntity.getCreatedAt()))
            .toList();
    }

    /** Screen 1's "Open discrepancies: N" — unresolved cases (OPEN + DISPUTED). Not the credit ledger's open count. */
    @Transactional(readOnly = true)
    public DiscrepancyStatsResponse stats() {
        long open = caseRepository.findAll().stream().filter(DiscrepancyCase::isActive).count();
        return new DiscrepancyStatsResponse(open);
    }

    @Transactional(readOnly = true)
    public DiscrepancyViewResponse getView(UUID caseId) {
        DiscrepancyCase caseEntity = caseRepository.findById(caseId)
            .orElseThrow(() -> new NotFoundException("Discrepancy case not found"));
        TenantGuard.assertOwned(caseEntity);
        UUID poId = caseEntity.getPurchaseOrderId();

        List<LegLine> poLines = purchaseOrderService.getReconciliationView(poId).lines().stream()
            .map(line -> new LegLine(line.skuId(), line.quantity(), line.unitPriceAmount()))
            .toList();

        Optional<ConfirmedProformaInvoiceView> pi = proformaInvoiceMatchService.getConfirmedForMatch(poId);
        List<LegLine> piLines = pi.map(view -> view.lines().stream()
                .map(line -> new LegLine(line.skuId(), line.confirmedQuantity(), line.confirmedUnitPriceAmount()))
                .toList())
            .orElse(List.of());

        List<GrnLegLine> grnLines = grnProjectionLineRepository.findAll().stream()
            .filter(line -> line.getPurchaseOrderId().equals(poId))
            .map(line -> new GrnLegLine(line.getSkuId(), line.getReceivedQuantity()))
            .toList();

        Optional<Invoice> invoice = invoiceRepository.findAll().stream()
            .filter(inv -> inv.getPurchaseOrderId().equals(poId) && inv.isActive())
            .findFirst();
        InvoiceLeg invoiceLeg = invoice.map(inv -> {
            Money claimed = inv.getClaimedCredit();
            return new InvoiceLeg(inv.getAmount().amount(), inv.getAmount().currency(),
                claimed == null ? null : claimed.amount(), inv.getClaimedCreditReference());
        }).orElse(null);

        String claimedCreditVerdict = invoice
            .map(Invoice::getClaimedCredit)
            .map(claimed -> creditLedgerService.checkClaim(poId, claimed).outcome().name())
            .orElse(null);

        List<CreditLedgerEntryResponse> openLedgerEntries = creditLedgerService.list(CreditLedgerStatus.OPEN, poId);

        return new DiscrepancyViewResponse(
            caseEntity.getId(), caseEntity.getPaymentId(), poId,
            purchaseOrderService.getSummary(poId).poNumber(),
            caseEntity.getStatus(), caseEntity.getResolutionType(), caseEntity.getFailureDetail(),
            caseEntity.getClaimedBy(), caseEntity.getClaimedAt(), caseEntity.getResolvedBy(),
            caseEntity.getResolvedAt(), caseEntity.getResolutionReason(), caseEntity.getCreditLedgerEntryId(),
            poLines, piLines, grnLines, invoiceLeg, claimedCreditVerdict, openLedgerEntries,
            caseEntity.getCreatedAt(), caseEntity.getUpdatedAt());
    }
}
