package com.shvoy.payments.service;

import java.util.Currency;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.CurrentUserContext;
import com.shvoy.Money;
import com.shvoy.ValidationException;
import com.shvoy.payments.domain.Invoice;
import com.shvoy.payments.dto.LogInvoiceRequest;
import com.shvoy.payments.repository.InvoiceRepository;
import com.shvoy.purchaseorders.service.PurchaseOrderService;

/**
 * The actual "record an invoice" write — Story 6.4, mirroring {@code
 * ProformaInvoiceRecordingService} (5.2). Its own {@code @Transactional} on a
 * separate bean so {@code InvoiceService#log} commits the invoice durably
 * <em>before</em> firing the anchor-date trigger, exactly as PI logging commits
 * before reconciliation.
 *
 * <p>Validation is well-formedness only: the PO must be finalised ({@code
 * GENERATED}/{@code SENT} — else {@code PO_NOT_READY_FOR_INVOICE}) and the
 * currency a real ISO 4217 code. Amount/currency disagreement with the PO is
 * recorded, never rejected. One active invoice per PO: a new one supersedes the
 * prior active (kept for audit).
 */
@Service
class InvoiceRecordingService {

    private final InvoiceRepository invoiceRepository;
    private final PurchaseOrderService purchaseOrderService;

    InvoiceRecordingService(InvoiceRepository invoiceRepository, PurchaseOrderService purchaseOrderService) {
        this.invoiceRepository = invoiceRepository;
        this.purchaseOrderService = purchaseOrderService;
    }

    @Transactional
    UUID recordInvoice(UUID purchaseOrderId, LogInvoiceRequest request) {
        purchaseOrderService.assertOwnPurchaseOrderReadyForInvoice(purchaseOrderId);
        Money amount = buildAmount(request.amount(), request.currency());

        supersedeCurrentActiveInvoice(purchaseOrderId);

        Invoice invoice = invoiceRepository.save(new Invoice(
            purchaseOrderId, request.invoiceReference(), amount, request.invoiceDate(),
            request.claimedCreditAmount(), request.claimedCreditReference(), CurrentUserContext.get()));
        return invoice.getId();
    }

    /** At most one active invoice per PO (the MVP cardinality) — supersede the prior active before saving the new one. */
    private void supersedeCurrentActiveInvoice(UUID purchaseOrderId) {
        invoiceRepository.findAll().stream()
            .filter(invoice -> invoice.getPurchaseOrderId().equals(purchaseOrderId) && invoice.isActive())
            .forEach(invoice -> {
                invoice.supersede();
                invoiceRepository.save(invoice);
            });
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
