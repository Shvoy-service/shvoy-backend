package com.shvoy.reconciliation.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.UnitPrice;
import com.shvoy.purchaseorders.service.PurchaseOrderService;
import com.shvoy.reconciliation.domain.ProformaInvoice;
import com.shvoy.reconciliation.domain.ProformaInvoiceLine;
import com.shvoy.reconciliation.dto.LogProformaInvoiceRequest;
import com.shvoy.reconciliation.dto.ProformaInvoiceLineResponse;
import com.shvoy.reconciliation.dto.ProformaInvoiceResponse;
import com.shvoy.reconciliation.repository.ProformaInvoiceLineRepository;
import com.shvoy.reconciliation.repository.ProformaInvoiceRepository;

/**
 * Story 5.2: log a supplier's confirmed PI against a PO, and read it back.
 *
 * {@link #log} is the single "record a PI" entry point (scope item 5) — the
 * controller calls it today with a request built from a human's form
 * submission; a future AI Document Intelligence pipeline (roadmap Feature 8)
 * would call the exact same method with a request built from whatever it
 * extracted from the supplier's document. Manual entry and that future feed
 * converge here rather than following parallel paths, at no cost beyond
 * routing both through one method.
 */
@Service
public class ProformaInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(ProformaInvoiceService.class);

    private final ProformaInvoiceRepository proformaInvoiceRepository;
    private final ProformaInvoiceLineRepository proformaInvoiceLineRepository;
    private final PurchaseOrderService purchaseOrderService;
    private final ProformaInvoiceRecordingService proformaInvoiceRecordingService;
    private final ReconciliationTriggerService reconciliationTriggerService;

    ProformaInvoiceService(
            ProformaInvoiceRepository proformaInvoiceRepository,
            ProformaInvoiceLineRepository proformaInvoiceLineRepository,
            PurchaseOrderService purchaseOrderService,
            ProformaInvoiceRecordingService proformaInvoiceRecordingService,
            ReconciliationTriggerService reconciliationTriggerService) {
        this.proformaInvoiceRepository = proformaInvoiceRepository;
        this.proformaInvoiceLineRepository = proformaInvoiceLineRepository;
        this.purchaseOrderService = purchaseOrderService;
        this.proformaInvoiceRecordingService = proformaInvoiceRecordingService;
        this.reconciliationTriggerService = reconciliationTriggerService;
    }

    /**
     * Records the PI ({@link ProformaInvoiceRecordingService#recordPi}, its
     * own transaction — see that class's Javadoc) and only then fires the
     * post-log reconciliation seam ({@link ReconciliationTriggerService},
     * stubbed until 5.3). The trigger runs outside any transaction and its
     * failure is swallowed (logged, not rethrown): the logging endpoint's
     * success must never depend on reconciliation succeeding, per this
     * story's scope item 4.
     */
    public ProformaInvoiceResponse log(UUID purchaseOrderId, LogProformaInvoiceRequest request) {
        UUID proformaInvoiceId = proformaInvoiceRecordingService.recordPi(purchaseOrderId, request);
        try {
            reconciliationTriggerService.onPiLogged(proformaInvoiceId);
        } catch (RuntimeException e) {
            log.warn("Reconciliation trigger failed for PI {} — PI remains logged", proformaInvoiceId, e);
        }
        return get(proformaInvoiceId);
    }

    @Transactional(readOnly = true)
    public ProformaInvoiceResponse get(UUID id) {
        return toResponse(findOwnProformaInvoice(id));
    }

    /** Newest first; includes the active PI and any it superseded. */
    @Transactional(readOnly = true)
    public List<ProformaInvoiceResponse> listForPurchaseOrder(UUID purchaseOrderId) {
        purchaseOrderService.assertOwnPurchaseOrderExists(purchaseOrderId);
        return proformaInvoiceRepository.findAll().stream()
            .filter(pi -> pi.getPurchaseOrderId().equals(purchaseOrderId))
            .sorted(Comparator.comparing(ProformaInvoice::getCreatedAt).reversed())
            .map(this::toResponse)
            .toList();
    }

    private ProformaInvoice findOwnProformaInvoice(UUID id) {
        ProformaInvoice proformaInvoice = proformaInvoiceRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Proforma invoice not found"));
        TenantGuard.assertOwned(proformaInvoice);
        return proformaInvoice;
    }

    private ProformaInvoiceResponse toResponse(ProformaInvoice proformaInvoice) {
        List<ProformaInvoiceLineResponse> lines = proformaInvoiceLineRepository.findAll().stream()
            .filter(line -> line.getProformaInvoiceId().equals(proformaInvoice.getId()))
            .sorted(Comparator.comparingInt(ProformaInvoiceLine::getLineNumber))
            .map(line -> toLineResponse(line, proformaInvoice.getCurrency()))
            .toList();

        return new ProformaInvoiceResponse(
            proformaInvoice.getId(),
            proformaInvoice.getPurchaseOrderId(),
            proformaInvoice.getPiReference(),
            proformaInvoice.getCurrency(),
            proformaInvoice.getStatus(),
            proformaInvoice.isActive(),
            proformaInvoice.getLoggedBy(),
            lines,
            proformaInvoice.getCreatedAt(),
            proformaInvoice.getUpdatedAt());
    }

    /** {@code line}'s currency is always its parent PI's — see {@code ProformaInvoiceLine}'s Javadoc. */
    private static ProformaInvoiceLineResponse toLineResponse(ProformaInvoiceLine line, String currency) {
        return new ProformaInvoiceLineResponse(
            line.getId(),
            line.getSkuId(),
            line.getLineNumber(),
            new UnitPrice(line.getConfirmedUnitPriceAmount(), currency),
            line.getConfirmedQuantity(),
            line.getCreatedAt());
    }
}
