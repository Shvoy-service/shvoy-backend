package com.shvoy.reconciliation.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.purchaseorders.service.PurchaseOrderService;
import com.shvoy.reconciliation.domain.ProformaInvoice;
import com.shvoy.reconciliation.domain.ProformaInvoiceStatus;
import com.shvoy.reconciliation.domain.Reconciliation;
import com.shvoy.reconciliation.domain.ReconciliationAuditEvent;
import com.shvoy.reconciliation.dto.ProformaInvoiceResponse;
import com.shvoy.reconciliation.dto.ReconciliationAuditEventResponse;
import com.shvoy.reconciliation.dto.ReconciliationDetailResponse;
import com.shvoy.reconciliation.dto.ReconciliationResponse;
import com.shvoy.reconciliation.dto.ReconciliationSummaryResponse;
import com.shvoy.reconciliation.repository.ProformaInvoiceRepository;
import com.shvoy.reconciliation.repository.ReconciliationRepository;

/**
 * The read side of Story 5.7 — the queries Screen 4 and the Feature 7
 * dashboard need. Composes the existing per-concern services rather than
 * re-deriving their data: the consolidated detail is the PI (5.2) + the
 * comparison (5.3/5.4) + the approval progress (5.5) + the audit trail — each
 * still owned by its own service — assembled in one call so the frontend
 * doesn't stitch several.
 */
@Service
public class ReconciliationQueryService {

    private final ProformaInvoiceService proformaInvoiceService;
    private final ReconciliationService reconciliationService;
    private final PiApprovalService piApprovalService;
    private final ReconciliationAuditService reconciliationAuditService;
    private final ReconciliationRepository reconciliationRepository;
    private final ProformaInvoiceRepository proformaInvoiceRepository;
    private final PurchaseOrderService purchaseOrderService;

    ReconciliationQueryService(
            ProformaInvoiceService proformaInvoiceService,
            ReconciliationService reconciliationService,
            PiApprovalService piApprovalService,
            ReconciliationAuditService reconciliationAuditService,
            ReconciliationRepository reconciliationRepository,
            ProformaInvoiceRepository proformaInvoiceRepository,
            PurchaseOrderService purchaseOrderService) {
        this.proformaInvoiceService = proformaInvoiceService;
        this.reconciliationService = reconciliationService;
        this.piApprovalService = piApprovalService;
        this.reconciliationAuditService = reconciliationAuditService;
        this.reconciliationRepository = reconciliationRepository;
        this.proformaInvoiceRepository = proformaInvoiceRepository;
        this.purchaseOrderService = purchaseOrderService;
    }

    /** Everything Screen 4 needs for one PI, in a single call. Each nested piece tenant-guards itself. */
    @Transactional(readOnly = true)
    public ReconciliationDetailResponse getDetail(UUID proformaInvoiceId) {
        ProformaInvoiceResponse pi = proformaInvoiceService.get(proformaInvoiceId);
        ReconciliationResponse reconciliation = reconciliationService.getForProformaInvoice(proformaInvoiceId);
        List<ReconciliationAuditEventResponse> auditTrail = reconciliationAuditService.trailFor(proformaInvoiceId).stream()
            .map(ReconciliationQueryService::toAuditResponse)
            .toList();
        return new ReconciliationDetailResponse(
            pi, reconciliation, piApprovalService.getApprovalState(proformaInvoiceId), auditTrail);
    }

    /** Every reconciliation logged against a PO, including superseded PIs — newest first. */
    @Transactional(readOnly = true)
    public List<ReconciliationSummaryResponse> listForPurchaseOrder(UUID purchaseOrderId) {
        purchaseOrderService.assertOwnPurchaseOrderExists(purchaseOrderId);
        return latestReconciliationPerPi().values().stream()
            .filter(reconciliation -> reconciliation.getPurchaseOrderId().equals(purchaseOrderId))
            .sorted(Comparator.comparing(Reconciliation::getCreatedAt).reversed())
            .map(this::toSummary)
            .toList();
    }

    /** The approver's queue: reconciliations whose PI is currently awaiting a decision — newest first. */
    @Transactional(readOnly = true)
    public List<ReconciliationSummaryResponse> listPendingApproval() {
        Map<UUID, ProformaInvoice> pisById = proformaInvoiceRepository.findAll().stream()
            .collect(Collectors.toMap(ProformaInvoice::getId, Function.identity()));
        return latestReconciliationPerPi().values().stream()
            .filter(reconciliation -> {
                ProformaInvoice pi = pisById.get(reconciliation.getProformaInvoiceId());
                return pi != null && pi.getStatus() == ProformaInvoiceStatus.ROUTED_FOR_APPROVAL;
            })
            .sorted(Comparator.comparing(Reconciliation::getCreatedAt).reversed())
            .map(this::toSummary)
            .toList();
    }

    /** At most one summary per PI — its latest reconciliation, so a re-triggered comparison doesn't double-list. */
    private Map<UUID, Reconciliation> latestReconciliationPerPi() {
        return reconciliationRepository.findAll().stream()
            .collect(Collectors.toMap(
                Reconciliation::getProformaInvoiceId,
                Function.identity(),
                (a, b) -> a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b));
    }

    private ReconciliationSummaryResponse toSummary(Reconciliation reconciliation) {
        Optional<ProformaInvoice> pi = proformaInvoiceRepository.findById(reconciliation.getProformaInvoiceId());
        return new ReconciliationSummaryResponse(
            reconciliation.getProformaInvoiceId(),
            reconciliation.getPurchaseOrderId(),
            reconciliation.getSupplierId(),
            pi.map(ProformaInvoice::getPiReference).orElse(null),
            reconciliation.getPiCurrency(),
            pi.map(ProformaInvoice::getStatus).orElse(null),
            pi.map(ProformaInvoice::isActive).orElse(false),
            reconciliation.getOutcome(),
            reconciliation.getCreatedAt());
    }

    private static ReconciliationAuditEventResponse toAuditResponse(ReconciliationAuditEvent event) {
        return new ReconciliationAuditEventResponse(
            event.getEventType(), event.getActorUserId(), event.getDetail(), event.getCreatedAt());
    }
}
