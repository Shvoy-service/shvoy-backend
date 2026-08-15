package com.shvoy.payments.service;

import java.util.Currency;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.CurrentUserContext;
import com.shvoy.Money;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.ValidationException;
import com.shvoy.payments.domain.Invoice;
import com.shvoy.payments.domain.InvoiceCoveredLine;
import com.shvoy.payments.domain.InvoiceCoversType;
import com.shvoy.payments.dto.LogInvoiceRequest;
import com.shvoy.payments.repository.InvoiceCoveredLineRepository;
import com.shvoy.payments.repository.InvoiceRepository;
import com.shvoy.purchaseorders.service.PurchaseOrderService;

/**
 * The actual "record an invoice" write — Story 6.4, remodelled (invoice
 * remodel). Its own {@code @Transactional} on a separate bean so {@code
 * InvoiceService#log} commits the invoice durably <em>before</em> firing the
 * anchor-date trigger, exactly as PI logging commits before reconciliation.
 *
 * <p><strong>Cardinality change.</strong> Under the remodel a PO can have many
 * concurrent active invoices (a deposit invoice, a per-shipment invoice, a
 * lines invoice — all live at once). Logging therefore <em>no longer
 * supersedes</em> the prior active invoice: {@link #recordInvoice} just adds a
 * new active one. Supersession is now an explicit correction of one specific
 * invoice ({@link #recordCorrection}), not "replace the PO's invoice".
 *
 * <p>Validation stays well-formedness only: the PO must be finalised, the
 * currency a real ISO 4217 code, and the declared coverage must be coherent at
 * entry (existence/ownership — {@link InvoiceCoverageValidator}). Amount vs. what
 * it covers is recorded, never rejected; the match (6.5) judges it.
 */
@Service
class InvoiceRecordingService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceCoveredLineRepository invoiceCoveredLineRepository;
    private final InvoiceCoverageValidator coverageValidator;
    private final PurchaseOrderService purchaseOrderService;

    InvoiceRecordingService(InvoiceRepository invoiceRepository,
            InvoiceCoveredLineRepository invoiceCoveredLineRepository, InvoiceCoverageValidator coverageValidator,
            PurchaseOrderService purchaseOrderService) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceCoveredLineRepository = invoiceCoveredLineRepository;
        this.coverageValidator = coverageValidator;
        this.purchaseOrderService = purchaseOrderService;
    }

    /** Record a new active invoice against the PO. Does NOT supersede any existing invoice (many-per-PO). */
    @Transactional
    UUID recordInvoice(UUID purchaseOrderId, LogInvoiceRequest request) {
        purchaseOrderService.assertOwnPurchaseOrderReadyForInvoice(purchaseOrderId);
        coverageValidator.validate(purchaseOrderId, request);
        return save(purchaseOrderId, request, null);
    }

    /**
     * Correct one specific invoice: supersede it (kept for audit) and record its
     * replacement, chained via {@code supersedes_invoice_id}. This is the only
     * path that supersedes anything now — logging never does.
     */
    @Transactional
    UUID recordCorrection(UUID invoiceId, LogInvoiceRequest request) {
        Invoice corrected = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new NotFoundException("Invoice not found"));
        TenantGuard.assertOwned(corrected);
        UUID purchaseOrderId = corrected.getPurchaseOrderId();

        coverageValidator.validate(purchaseOrderId, request);
        corrected.supersede();
        invoiceRepository.save(corrected);
        return save(purchaseOrderId, request, invoiceId);
    }

    private UUID save(UUID purchaseOrderId, LogInvoiceRequest request, UUID supersedesInvoiceId) {
        Money amount = buildAmount(request.amount(), request.currency());
        Invoice invoice = invoiceRepository.save(new Invoice(
            purchaseOrderId, request.invoiceReference(), amount, request.invoiceDate(),
            request.claimedCreditAmount(), request.claimedCreditReference(),
            request.coversType(), request.coversConsignmentId(), supersedesInvoiceId, CurrentUserContext.get()));

        if (request.coversType() == InvoiceCoversType.LINES && request.coveredLines() != null) {
            request.coveredLines().forEach(line -> invoiceCoveredLineRepository.save(
                new InvoiceCoveredLine(invoice.getId(), line.skuId(), line.quantity())));
        }
        return invoice.getId();
    }

    private static Money buildAmount(java.math.BigDecimal amount, String currency) {
        try {
            Currency.getInstance(currency);
            return new Money(amount, currency);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("currency: " + e.getMessage());
        }
    }
}
