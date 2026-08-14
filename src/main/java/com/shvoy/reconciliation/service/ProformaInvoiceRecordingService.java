package com.shvoy.reconciliation.service;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.CurrentUserContext;
import com.shvoy.ValidationException;
import com.shvoy.purchaseorders.service.PurchaseOrderService;
import com.shvoy.reconciliation.domain.ProformaInvoice;
import com.shvoy.reconciliation.domain.ProformaInvoiceLine;
import com.shvoy.reconciliation.domain.ReconciliationAuditEventType;
import com.shvoy.reconciliation.dto.LogProformaInvoiceRequest;
import com.shvoy.reconciliation.dto.ProformaInvoiceLineRequest;
import com.shvoy.reconciliation.repository.ProformaInvoiceLineRepository;
import com.shvoy.reconciliation.repository.ProformaInvoiceRepository;
import com.shvoy.suppliers.service.SkuService;

/**
 * The actual "record a PI" write — Story 5.2. Validation here is
 * well-formedness only (see {@link ProformaInvoiceLineRequest}'s Javadoc):
 * the PO must be ready ({@code GENERATED}/{@code SENT} — {@link
 * PurchaseOrderService#assertOwnPurchaseOrderReadyForPi}), each SKU must
 * exist somewhere in the caller's company ({@link SkuService#assertOwnSkuExists},
 * deliberately not scoped to the PO's own supplier — see that method's
 * Javadoc), and currency must be a real ISO 4217 code. Price/quantity/
 * currency disagreement with the PO is recorded, never rejected — that's
 * exactly what reconciliation (5.3+) exists to detect.
 *
 * Package-private and a separate bean from {@code ProformaInvoiceService}
 * on purpose: {@code ProformaInvoiceService#log} calls {@link #recordPi}
 * through this bean's own Spring proxy so the {@code @Transactional}
 * boundary below actually commits — durably recording the PI — before the
 * post-log reconciliation trigger runs. A failure in that later step can
 * then never lose an already-logged PI. Self-invocation from within
 * {@code ProformaInvoiceService} couldn't provide that guarantee (a
 * same-class method call bypasses the transactional proxy entirely), and
 * {@code REQUIRES_NEW} was deliberately avoided (see the connection-pool
 * deadlock this codebase already hit with it in {@code PoNumberGenerator}) —
 * this is a plain, separate, top-level transaction instead.
 */
@Service
class ProformaInvoiceRecordingService {

    private final ProformaInvoiceRepository proformaInvoiceRepository;
    private final ProformaInvoiceLineRepository proformaInvoiceLineRepository;
    private final PurchaseOrderService purchaseOrderService;
    private final SkuService skuService;
    private final ReconciliationAuditService reconciliationAuditService;

    ProformaInvoiceRecordingService(
            ProformaInvoiceRepository proformaInvoiceRepository,
            ProformaInvoiceLineRepository proformaInvoiceLineRepository,
            PurchaseOrderService purchaseOrderService,
            SkuService skuService,
            ReconciliationAuditService reconciliationAuditService) {
        this.proformaInvoiceRepository = proformaInvoiceRepository;
        this.proformaInvoiceLineRepository = proformaInvoiceLineRepository;
        this.purchaseOrderService = purchaseOrderService;
        this.skuService = skuService;
        this.reconciliationAuditService = reconciliationAuditService;
    }

    @Transactional
    UUID recordPi(UUID purchaseOrderId, LogProformaInvoiceRequest request) {
        purchaseOrderService.assertOwnPurchaseOrderReadyForPi(purchaseOrderId);
        String currency = validateCurrency(request.currency());
        request.lines().forEach(line -> skuService.assertOwnSkuExists(line.skuId()));

        supersedeCurrentActivePi(purchaseOrderId);

        UUID loggedBy = CurrentUserContext.get();
        ProformaInvoice proformaInvoice = proformaInvoiceRepository.save(
            new ProformaInvoice(purchaseOrderId, request.piReference(), currency, loggedBy));

        List<ProformaInvoiceLine> lines = new ArrayList<>();
        int lineNumber = 1;
        for (ProformaInvoiceLineRequest lineRequest : request.lines()) {
            lines.add(new ProformaInvoiceLine(proformaInvoice.getId(), lineRequest.skuId(), lineNumber++,
                lineRequest.confirmedUnitPriceAmount(), lineRequest.confirmedQuantity()));
        }
        proformaInvoiceLineRepository.saveAll(lines);

        reconciliationAuditService.record(proformaInvoice.getId(), null,
            ReconciliationAuditEventType.PI_LOGGED, loggedBy,
            "Logged PI " + request.piReference() + " (" + currency + ") with " + lines.size() + " line(s)");

        return proformaInvoice.getId();
    }

    /**
     * Story 5.1's cardinality decision, actually implemented: at most one
     * PI is active per PO at a time. No DB lock backs this against
     * concurrent double-submission for the same PO — an accepted, narrow
     * gap, same shape as {@code SkuPrice}'s price-window supersession
     * before a later review added a {@code SELECT ... FOR UPDATE} lock
     * there; worth the same treatment if this is ever actually hit under
     * load.
     */
    private void supersedeCurrentActivePi(UUID purchaseOrderId) {
        proformaInvoiceRepository.findAll().stream()
            .filter(pi -> pi.getPurchaseOrderId().equals(purchaseOrderId) && pi.isActive())
            .forEach(pi -> {
                pi.supersede();
                proformaInvoiceRepository.save(pi);
                reconciliationAuditService.record(pi.getId(), null,
                    ReconciliationAuditEventType.SUPERSEDED, null,
                    "Superseded by a corrected PI logged against the same PO");
            });
    }

    private static String validateCurrency(String currency) {
        try {
            Currency.getInstance(currency);
            return currency;
        } catch (IllegalArgumentException e) {
            throw new ValidationException("currency: " + e.getMessage());
        }
    }
}
