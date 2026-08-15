package com.shvoy.payments.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.payments.domain.Invoice;
import com.shvoy.payments.domain.InvoiceCoversType;
import com.shvoy.payments.dto.InvoiceCoveredLineResponse;
import com.shvoy.payments.dto.InvoiceResponse;
import com.shvoy.payments.dto.LogInvoiceRequest;
import com.shvoy.payments.event.AnchorEventDateKnownEvent;
import com.shvoy.payments.repository.InvoiceCoveredLineRepository;
import com.shvoy.payments.repository.InvoiceRepository;
import com.shvoy.purchaseorders.service.PurchaseOrderService;
import com.shvoy.suppliers.domain.AnchorEvent;

/**
 * Story 6.4 — log a supplier's final invoice against a PO, and read it back;
 * remodelled (invoice remodel) for many concurrent invoices per PO, declared
 * coverage, and correction-as-supersession.
 *
 * <p>{@link #log} records a <em>new</em> active invoice (it no longer supersedes
 * the PO's prior one). {@link #correct} is the explicit supersession path,
 * correcting one specific invoice. Both are the single internal "record"
 * surface the manual endpoint and a future AI extraction feed converge on.
 *
 * <p><strong>The anchor-date trigger.</strong> After the invoice is durably
 * recorded, the INVOICE anchor is (re-)published <em>only when the anchoring
 * policy says so</em> — the first non-deposit invoice, or a correction of it
 * (see {@link InvoiceAnchorPolicy}, the one isolated knob). {@code
 * PaymentDueDateService} (6.2) reacts. Best-effort: a trigger failure never
 * fails the logging.
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final InvoiceRepository invoiceRepository;
    private final InvoiceCoveredLineRepository invoiceCoveredLineRepository;
    private final InvoiceRecordingService invoiceRecordingService;
    private final InvoiceAnchorPolicy invoiceAnchorPolicy;
    private final PurchaseOrderService purchaseOrderService;
    private final ApplicationEventPublisher eventPublisher;

    InvoiceService(InvoiceRepository invoiceRepository, InvoiceCoveredLineRepository invoiceCoveredLineRepository,
            InvoiceRecordingService invoiceRecordingService, InvoiceAnchorPolicy invoiceAnchorPolicy,
            PurchaseOrderService purchaseOrderService, ApplicationEventPublisher eventPublisher) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceCoveredLineRepository = invoiceCoveredLineRepository;
        this.invoiceRecordingService = invoiceRecordingService;
        this.invoiceAnchorPolicy = invoiceAnchorPolicy;
        this.purchaseOrderService = purchaseOrderService;
        this.eventPublisher = eventPublisher;
    }

    /** Log a new active invoice against the PO (many-per-PO; supersedes nothing). */
    public InvoiceResponse log(UUID purchaseOrderId, LogInvoiceRequest request) {
        UUID invoiceId = invoiceRecordingService.recordInvoice(purchaseOrderId, request);
        afterRecorded(purchaseOrderId, invoiceId, request.invoiceDate());
        return get(invoiceId);
    }

    /** Correct one specific invoice — supersede it and record its replacement. */
    public InvoiceResponse correct(UUID invoiceId, LogInvoiceRequest request) {
        UUID newInvoiceId = invoiceRecordingService.recordCorrection(invoiceId, request);
        afterRecorded(get(newInvoiceId).purchaseOrderId(), newInvoiceId, request.invoiceDate());
        return get(newInvoiceId);
    }

    private void afterRecorded(UUID purchaseOrderId, UUID invoiceId, java.time.LocalDate invoiceDate) {
        if (invoiceAnchorPolicy.shouldPublishAnchor(purchaseOrderId, invoiceId)) {
            try {
                eventPublisher.publishEvent(
                    new AnchorEventDateKnownEvent(purchaseOrderId, AnchorEvent.INVOICE, invoiceDate));
            } catch (RuntimeException e) {
                log.warn("Invoice-date anchor trigger failed for PO {} — invoice {} remains logged",
                    purchaseOrderId, invoiceId, e);
            }
        }
        // The invoice is the fourth leg of the three-way match (6.5) — re-evaluate now it exists/changed.
        eventPublisher.publishEvent(new MatchInputChangedEvent(purchaseOrderId));
    }

    @Transactional(readOnly = true)
    public InvoiceResponse get(UUID id) {
        return toResponse(findOwnInvoice(id));
    }

    /** Newest first; includes the active invoices and any they superseded. */
    @Transactional(readOnly = true)
    public List<InvoiceResponse> listForPurchaseOrder(UUID purchaseOrderId) {
        purchaseOrderService.assertOwnPurchaseOrderExists(purchaseOrderId);
        return invoiceRepository.findAll().stream()
            .filter(invoice -> invoice.getPurchaseOrderId().equals(purchaseOrderId))
            .sorted(Comparator.comparing(Invoice::getCreatedAt).reversed())
            .map(this::toResponse)
            .toList();
    }

    private Invoice findOwnInvoice(UUID id) {
        Invoice invoice = invoiceRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Invoice not found"));
        TenantGuard.assertOwned(invoice);
        return invoice;
    }

    private InvoiceResponse toResponse(Invoice invoice) {
        List<InvoiceCoveredLineResponse> coveredLines = invoice.getCoversType() == InvoiceCoversType.LINES
            ? invoiceCoveredLineRepository.findAll().stream()
                .filter(line -> line.getInvoiceId().equals(invoice.getId()))
                .map(line -> new InvoiceCoveredLineResponse(line.getSkuId(), line.getQuantity()))
                .toList()
            : List.of();
        return new InvoiceResponse(
            invoice.getId(),
            invoice.getPurchaseOrderId(),
            invoice.getInvoiceReference(),
            invoice.getAmount(),
            invoice.getInvoiceDate(),
            invoice.getClaimedCredit(),
            invoice.getClaimedCreditReference(),
            invoice.getStatus(),
            invoice.isActive(),
            invoice.getCoversType(),
            invoice.getCoversConsignmentId(),
            coveredLines,
            invoice.getSupersedesInvoiceId(),
            invoice.getCoversType() == InvoiceCoversType.AMOUNT,
            invoice.getLoggedBy(),
            invoice.getCreatedAt(),
            invoice.getUpdatedAt());
    }
}
