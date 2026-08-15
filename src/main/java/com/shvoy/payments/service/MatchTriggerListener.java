package com.shvoy.payments.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import com.shvoy.payments.event.ProvisionalGoodsReceiptEvent;
import com.shvoy.purchaseorders.event.PurchaseOrderGeneratedEvent;
import com.shvoy.reconciliation.event.ProformaInvoiceConfirmedEvent;

/**
 * Drives the three-way match event-driven (Story 6.5). It re-evaluates a PO's
 * match whenever any leg arrives or changes:
 * <ul>
 *   <li>PO generated (payments created, deposit becomes payable) — {@code purchaseorders}.</li>
 *   <li>Confirmed PI — {@code reconciliation} (5.4/5.5).</li>
 *   <li>Provisional GRN created/amended — {@code shipments} (7.4); its quantities are projected first.</li>
 *   <li>Invoice logged/superseded, or a credit logged — payments-internal ({@code MatchInputChangedEvent}).</li>
 * </ul>
 *
 * <p><strong>{@code AFTER_COMMIT}</strong> so the match reacts to durable data —
 * a publisher's write is committed before the match reads it, and a match
 * failure can never roll back the write that triggered it (best-effort:
 * exceptions are logged and swallowed). {@code fallbackExecution = true} so
 * events published outside a transaction (the GRN and invoice seams commit
 * first, then publish) still fire. All run synchronously on the publishing
 * thread, so the {@code TenantContext} ThreadLocal is intact.
 */
@Component
class MatchTriggerListener {

    private static final Logger log = LoggerFactory.getLogger(MatchTriggerListener.class);

    private final ThreeWayMatchService matchService;
    private final GrnProjectionService grnProjectionService;

    MatchTriggerListener(ThreeWayMatchService matchService, GrnProjectionService grnProjectionService) {
        this.matchService = matchService;
        this.grnProjectionService = grnProjectionService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onPurchaseOrderGenerated(PurchaseOrderGeneratedEvent event) {
        safelyEvaluate(event.purchaseOrderId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onProformaInvoiceConfirmed(ProformaInvoiceConfirmedEvent event) {
        safelyEvaluate(event.purchaseOrderId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onProvisionalGoodsReceipt(ProvisionalGoodsReceiptEvent event) {
        try {
            grnProjectionService.project(event);
        } catch (RuntimeException e) {
            log.warn("Failed to project GRN for PO {} — match not re-evaluated", event.purchaseOrderId(), e);
            return;
        }
        safelyEvaluate(event.purchaseOrderId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onMatchInputChanged(MatchInputChangedEvent event) {
        safelyEvaluate(event.purchaseOrderId());
    }

    private void safelyEvaluate(UUID purchaseOrderId) {
        try {
            matchService.evaluate(purchaseOrderId);
        } catch (RuntimeException e) {
            log.warn("Three-way match evaluation failed for PO {} — leaving payment state unchanged",
                purchaseOrderId, e);
        }
    }
}
