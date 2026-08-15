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
import com.shvoy.payments.dto.InvoiceResponse;
import com.shvoy.payments.dto.LogInvoiceRequest;
import com.shvoy.payments.event.AnchorEventDateKnownEvent;
import com.shvoy.payments.repository.InvoiceRepository;
import com.shvoy.purchaseorders.service.PurchaseOrderService;
import com.shvoy.suppliers.domain.AnchorEvent;

/**
 * Story 6.4 — log a supplier's final invoice against a PO, and read it back.
 *
 * {@link #log} is the single "record an invoice" entry point (mirroring {@code
 * ProformaInvoiceService#log}): the controller calls it today; a future AI
 * extraction pipeline would call the same method with an extracted request.
 *
 * <p><strong>The anchor-date trigger — this is the first real caller of 6.2's
 * seam.</strong> After the invoice is durably recorded, {@link #log} publishes
 * an {@link AnchorEventDateKnownEvent} for the {@code INVOICE} anchor with the
 * invoice's date. {@code PaymentDueDateService} (6.2) reacts and sets the due
 * date of any balance payment <em>anchored to the invoice</em> — payments
 * anchored to BL/arrival/ex-factory are untouched (they wait on Feature 7), so
 * the event is published unconditionally and the seam does the filtering. A
 * superseding invoice with a different date re-fires it; 6.2's re-entrancy
 * recalculates and audits. Best-effort: a trigger failure never fails the
 * logging, exactly as PI logging treats its reconciliation trigger.
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final InvoiceRepository invoiceRepository;
    private final InvoiceRecordingService invoiceRecordingService;
    private final PurchaseOrderService purchaseOrderService;
    private final ApplicationEventPublisher eventPublisher;

    InvoiceService(InvoiceRepository invoiceRepository, InvoiceRecordingService invoiceRecordingService,
            PurchaseOrderService purchaseOrderService, ApplicationEventPublisher eventPublisher) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceRecordingService = invoiceRecordingService;
        this.purchaseOrderService = purchaseOrderService;
        this.eventPublisher = eventPublisher;
    }

    public InvoiceResponse log(UUID purchaseOrderId, LogInvoiceRequest request) {
        UUID invoiceId = invoiceRecordingService.recordInvoice(purchaseOrderId, request);
        try {
            eventPublisher.publishEvent(
                new AnchorEventDateKnownEvent(purchaseOrderId, AnchorEvent.INVOICE, request.invoiceDate()));
        } catch (RuntimeException e) {
            log.warn("Invoice-date anchor trigger failed for PO {} — invoice {} remains logged",
                purchaseOrderId, invoiceId, e);
        }
        // The invoice is the fourth leg of the three-way match (6.5) — re-evaluate now it exists/changed.
        eventPublisher.publishEvent(new MatchInputChangedEvent(purchaseOrderId));
        return get(invoiceId);
    }

    @Transactional(readOnly = true)
    public InvoiceResponse get(UUID id) {
        return toResponse(findOwnInvoice(id));
    }

    /** Newest first; includes the active invoice and any it superseded. */
    @Transactional(readOnly = true)
    public List<InvoiceResponse> listForPurchaseOrder(UUID purchaseOrderId) {
        purchaseOrderService.assertOwnPurchaseOrderExists(purchaseOrderId);
        return invoiceRepository.findAll().stream()
            .filter(invoice -> invoice.getPurchaseOrderId().equals(purchaseOrderId))
            .sorted(Comparator.comparing(Invoice::getCreatedAt).reversed())
            .map(InvoiceService::toResponse)
            .toList();
    }

    private Invoice findOwnInvoice(UUID id) {
        Invoice invoice = invoiceRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Invoice not found"));
        TenantGuard.assertOwned(invoice);
        return invoice;
    }

    private static InvoiceResponse toResponse(Invoice invoice) {
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
            invoice.getLoggedBy(),
            invoice.getCreatedAt(),
            invoice.getUpdatedAt());
    }
}
