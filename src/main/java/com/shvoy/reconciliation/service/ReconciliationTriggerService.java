package com.shvoy.reconciliation.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.shvoy.reconciliation.domain.ReconciliationOutcome;

/**
 * The post-log reconciliation seam (Story 5.2's scope item 4, filled in by
 * 5.3). Deliberately called only after {@code ProformaInvoiceRecordingService#recordPi}'s
 * transaction has already committed (see {@code ProformaInvoiceService#log}),
 * so a failure here can never lose an already-logged PI, and the caller wraps
 * this call so a comparison failure is never a precondition of the logging
 * endpoint's own success.
 *
 * Kept as its own thin bean rather than folded into {@link
 * ReconciliationService}: it's the named seam the logging flow depends on
 * (and the future AI-document-intelligence feed would trigger the same way),
 * distinct from the comparison logic it delegates to.
 */
@Service
public class ReconciliationTriggerService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationTriggerService.class);

    private final ReconciliationService reconciliationService;
    private final ToleranceEvaluationService toleranceEvaluationService;
    private final ApproverNotifier approverNotifier;

    ReconciliationTriggerService(ReconciliationService reconciliationService,
            ToleranceEvaluationService toleranceEvaluationService,
            ApproverNotifier approverNotifier) {
        this.reconciliationService = reconciliationService;
        this.toleranceEvaluationService = toleranceEvaluationService;
        this.approverNotifier = approverNotifier;
    }

    /**
     * Compute the comparison (5.3), evaluate its outcome (5.4), and — when the
     * PI is routed for approval — notify the approvers (5.5). The first two are
     * distinct steps in their own transactions, so a reconciliation is durably
     * recorded even if evaluation later fails (leaving it in the "computed but
     * not yet evaluated" state {@code ToleranceEvaluationService} can re-run).
     * The whole call is already wrapped by {@code ProformaInvoiceService#log},
     * so any failure here — including a notification failure — never fails the
     * log; the approver notification is best-effort, same as PO-send email.
     */
    public void onPiLogged(UUID proformaInvoiceId) {
        UUID reconciliationId = reconciliationService.reconcile(proformaInvoiceId);
        ReconciliationOutcome outcome = toleranceEvaluationService.evaluate(reconciliationId);
        log.info("PI {} logged; reconciliation {} recorded, outcome {}", proformaInvoiceId, reconciliationId, outcome);
        if (outcome == ReconciliationOutcome.ROUTED_FOR_APPROVAL) {
            approverNotifier.notifyRouted(proformaInvoiceId);
        }
    }
}
