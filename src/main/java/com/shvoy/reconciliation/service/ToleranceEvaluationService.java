package com.shvoy.reconciliation.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.reconciliation.domain.ProformaInvoice;
import com.shvoy.reconciliation.domain.Reconciliation;
import com.shvoy.reconciliation.domain.ReconciliationFindingType;
import com.shvoy.reconciliation.event.ProformaInvoiceConfirmedEvent;
import com.shvoy.reconciliation.domain.ReconciliationLine;
import com.shvoy.reconciliation.domain.ReconciliationAuditEventType;
import com.shvoy.reconciliation.domain.ReconciliationOutcome;
import com.shvoy.reconciliation.repository.ProformaInvoiceRepository;
import com.shvoy.reconciliation.repository.ReconciliationLineRepository;
import com.shvoy.reconciliation.repository.ReconciliationRepository;

/**
 * Story 5.4 — the decision the whole feature exists for: take 5.3's computed,
 * stored variance and decide the outcome. Within tolerance on every line, no
 * structural finding, no currency mismatch → the PI auto-confirms; anything
 * else routes it to approval.
 *
 * <p>Reads the stored comparison and decides — it does not recompute the
 * variance (5.3 owns that) and it does not implement the approval workflow
 * (who approves, the 2-of-N gate — 5.5/5.6). Its output is an outcome plus a
 * reason; the deterministic boundary comparison lives in {@link
 * ToleranceEvaluator}.
 *
 * <p>Runs right after {@code ReconciliationService#reconcile} on the post-log
 * seam. The outcome is <strong>whole-PI</strong>: you don't part-confirm a
 * supplier's document, so any single failing line routes the entire PI, with
 * the per-line detail retained for the approver.
 */
@Service
public class ToleranceEvaluationService {

    private final ReconciliationRepository reconciliationRepository;
    private final ReconciliationLineRepository reconciliationLineRepository;
    private final ProformaInvoiceRepository proformaInvoiceRepository;
    private final ToleranceService toleranceService;
    private final ReconciliationAuditService reconciliationAuditService;
    private final ApplicationEventPublisher eventPublisher;

    ToleranceEvaluationService(
            ReconciliationRepository reconciliationRepository,
            ReconciliationLineRepository reconciliationLineRepository,
            ProformaInvoiceRepository proformaInvoiceRepository,
            ToleranceService toleranceService,
            ReconciliationAuditService reconciliationAuditService,
            ApplicationEventPublisher eventPublisher) {
        this.reconciliationRepository = reconciliationRepository;
        this.reconciliationLineRepository = reconciliationLineRepository;
        this.proformaInvoiceRepository = proformaInvoiceRepository;
        this.toleranceService = toleranceService;
        this.reconciliationAuditService = reconciliationAuditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ReconciliationOutcome evaluate(UUID reconciliationId) {
        Reconciliation reconciliation = reconciliationRepository.findById(reconciliationId)
            .orElseThrow(() -> new NotFoundException("Reconciliation not found"));
        TenantGuard.assertOwned(reconciliation);

        BigDecimal tolerance = toleranceService.resolveEffectiveTolerance();
        List<ReconciliationLine> lines = reconciliationLineRepository.findAll().stream()
            .filter(line -> line.getReconciliationId().equals(reconciliationId))
            .toList();

        ReconciliationOutcome outcome = decideOutcome(reconciliation, lines, tolerance);
        reconciliation.applyOutcome(outcome, tolerance);
        reconciliationRepository.save(reconciliation);

        applyToProformaInvoice(reconciliation.getProformaInvoiceId(), outcome);

        // Record the tolerance in force at the time, not just the outcome — so a historical
        // auto-confirm stays explicable ("passed because tolerance was 2% then") after a later change.
        reconciliationAuditService.record(reconciliation.getProformaInvoiceId(), reconciliationId,
            outcome == ReconciliationOutcome.AUTO_CONFIRMED
                ? ReconciliationAuditEventType.AUTO_CONFIRMED
                : ReconciliationAuditEventType.ROUTED_FOR_APPROVAL,
            null, "Evaluated against tolerance " + tolerance + "% (in force at the time); outcome " + outcome);
        return outcome;
    }

    /**
     * The whole-PI decision. Auto-confirm requires all three: no currency
     * mismatch, no structural finding, and every matched line strictly within
     * tolerance (a line whose variance couldn't be computed — e.g. a null
     * reference — can't be proven within tolerance, so it routes, the safe
     * default). Anything else routes. The single {@code <} that decides
     * "within tolerance" lives in {@link ToleranceEvaluator}, deliberately not
     * inlined into this compound condition where an edit could flip it.
     */
    private ReconciliationOutcome decideOutcome(Reconciliation reconciliation,
            List<ReconciliationLine> lines, BigDecimal tolerance) {
        if (reconciliation.isCurrencyMismatch()) {
            return ReconciliationOutcome.ROUTED_FOR_APPROVAL;
        }
        boolean anyStructuralFinding = lines.stream()
            .anyMatch(line -> line.getFindingType() != ReconciliationFindingType.MATCHED);
        if (anyStructuralFinding) {
            return ReconciliationOutcome.ROUTED_FOR_APPROVAL;
        }
        boolean everyMatchedLineWithinTolerance = lines.stream()
            .filter(line -> line.getFindingType() == ReconciliationFindingType.MATCHED)
            .allMatch(line -> line.getUnitPriceVariancePct() != null
                && ToleranceEvaluator.isWithinTolerance(line.getUnitPriceVariancePct().abs(), tolerance));
        return everyMatchedLineWithinTolerance
            ? ReconciliationOutcome.AUTO_CONFIRMED
            : ReconciliationOutcome.ROUTED_FOR_APPROVAL;
    }

    private void applyToProformaInvoice(UUID proformaInvoiceId, ReconciliationOutcome outcome) {
        ProformaInvoice proformaInvoice = proformaInvoiceRepository.findById(proformaInvoiceId)
            .orElseThrow(() -> new NotFoundException("Proforma invoice not found"));
        TenantGuard.assertOwned(proformaInvoice);
        if (outcome == ReconciliationOutcome.AUTO_CONFIRMED) {
            proformaInvoice.markAutoConfirmed();
        } else {
            proformaInvoice.markRoutedForApproval();
        }
        proformaInvoiceRepository.save(proformaInvoice);
        if (outcome == ReconciliationOutcome.AUTO_CONFIRMED) {
            // The confirmed-PI leg of the three-way match is now available (Story 6.5).
            eventPublisher.publishEvent(new ProformaInvoiceConfirmedEvent(proformaInvoice.getPurchaseOrderId()));
        }
    }
}
