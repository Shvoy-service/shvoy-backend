package com.shvoy.reconciliation.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.CurrentUserContext;
import com.shvoy.ErrorCode;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.onboarding.service.ApproverPoolService;
import com.shvoy.purchaseorders.service.PurchaseOrderService;
import com.shvoy.reconciliation.domain.ApprovalAction;
import com.shvoy.reconciliation.domain.ApprovalActionType;
import com.shvoy.reconciliation.domain.ProformaInvoice;
import com.shvoy.reconciliation.domain.ProformaInvoiceStatus;
import com.shvoy.reconciliation.domain.Reconciliation;
import com.shvoy.reconciliation.domain.ReconciliationAuditEventType;
import com.shvoy.reconciliation.domain.ReconciliationFindingType;
import com.shvoy.reconciliation.domain.ReconciliationLine;
import com.shvoy.reconciliation.dto.ApprovalActionResponse;
import com.shvoy.reconciliation.dto.ApprovalStateResponse;
import com.shvoy.reconciliation.repository.ApprovalActionRepository;
import com.shvoy.reconciliation.repository.ProformaInvoiceRepository;
import com.shvoy.reconciliation.repository.ReconciliationLineRepository;
import com.shvoy.reconciliation.repository.ReconciliationRepository;

/**
 * Story 5.5 — put a routed PI in front of the right people and let them
 * approve or reject it, applying the asymmetric rule: a price <em>increase</em>
 * beyond tolerance additionally requires N distinct sign-offs from the
 * approver pool, while decreases (and quantity/structural/currency issues with
 * no increase) route to approval but are resolvable by a single approver.
 *
 * <p>Two things stack, and mustn't be conflated: <strong>routing</strong> (any
 * out-of-tolerance/structural/currency PI goes to a human — 5.4, direction-
 * independent) and the <strong>2-of-N gate</strong> (an extra requirement,
 * triggered only by a price increase). See {@link #requiresSignOff}.
 *
 * <p>Records every decision as an immutable {@link ApprovalAction}; the PI
 * status transition (APPROVED on the Nth distinct sign-off / single approval,
 * REJECTED on the first rejection) mirrors that. The full lifecycle threading
 * and audit model is 5.7's — this story records the actions.
 */
@Service
public class PiApprovalService {

    private final ProformaInvoiceRepository proformaInvoiceRepository;
    private final ReconciliationRepository reconciliationRepository;
    private final ReconciliationLineRepository reconciliationLineRepository;
    private final ApprovalActionRepository approvalActionRepository;
    private final ApproverPoolService approverPoolService;
    private final PurchaseOrderService purchaseOrderService;
    private final ReconciliationAuditService reconciliationAuditService;

    PiApprovalService(
            ProformaInvoiceRepository proformaInvoiceRepository,
            ReconciliationRepository reconciliationRepository,
            ReconciliationLineRepository reconciliationLineRepository,
            ApprovalActionRepository approvalActionRepository,
            ApproverPoolService approverPoolService,
            PurchaseOrderService purchaseOrderService,
            ReconciliationAuditService reconciliationAuditService) {
        this.proformaInvoiceRepository = proformaInvoiceRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.reconciliationLineRepository = reconciliationLineRepository;
        this.approvalActionRepository = approvalActionRepository;
        this.approverPoolService = approverPoolService;
        this.purchaseOrderService = purchaseOrderService;
        this.reconciliationAuditService = reconciliationAuditService;
    }

    /**
     * Record an approver's sign-off. On the single-approver path (no price
     * increase) one approval confirms. On the 2-of-N path the approver must be
     * a distinct, currently-eligible pool member; the Nth distinct sign-off
     * confirms. Self-approval (the PO creator or PI logger) is forbidden on
     * both paths.
     */
    @Transactional
    public ApprovalStateResponse approve(UUID proformaInvoiceId, String comment) {
        ProformaInvoice pi = findAwaitingApproval(proformaInvoiceId);
        Reconciliation reconciliation = latestReconciliation(proformaInvoiceId);
        UUID actor = CurrentUserContext.get();
        assertNotSelfApproval(pi, actor);

        boolean requiresSignOff = requiresSignOff(reconciliation);
        if (requiresSignOff) {
            if (!approverPoolService.resolveEligibleApprovers().contains(actor)) {
                throw new ConflictException(ErrorCode.NOT_IN_APPROVER_POOL,
                    "Approving a price increase requires being an active member of the approver pool");
            }
            if (hasAlreadyApproved(proformaInvoiceId, actor)) {
                throw new ConflictException(ErrorCode.ALREADY_SIGNED_OFF,
                    "You have already signed off on this PI; the required sign-offs must come from distinct approvers");
            }
        }

        approvalActionRepository.save(new ApprovalAction(
            proformaInvoiceId, reconciliation.getId(), ApprovalActionType.APPROVE, actor, comment));

        boolean confirmed = !requiresSignOff || distinctApprovers(proformaInvoiceId).size() >= requiredApprovals(true);
        if (confirmed) {
            pi.markApproved();
            proformaInvoiceRepository.save(pi);
        }
        reconciliationAuditService.record(proformaInvoiceId, reconciliation.getId(),
            confirmed ? ReconciliationAuditEventType.APPROVED : ReconciliationAuditEventType.APPROVAL_RECORDED,
            actor, comment);
        return buildState(pi, reconciliation);
    }

    /**
     * Record a rejection — a single one is enough to reject the PI (the gate
     * guards against approving a price rise too easily, not against rejecting
     * one). Any approver may reject on either path, including the PO
     * creator/PI logger: flagging a problem with your own order isn't the
     * self-dealing self-approval guards against.
     */
    @Transactional
    public ApprovalStateResponse reject(UUID proformaInvoiceId, String reason) {
        ProformaInvoice pi = findAwaitingApproval(proformaInvoiceId);
        Reconciliation reconciliation = latestReconciliation(proformaInvoiceId);
        UUID actor = CurrentUserContext.get();

        approvalActionRepository.save(new ApprovalAction(
            proformaInvoiceId, reconciliation.getId(), ApprovalActionType.REJECT, actor, reason));
        pi.markRejected();
        proformaInvoiceRepository.save(pi);
        reconciliationAuditService.record(proformaInvoiceId, reconciliation.getId(),
            ReconciliationAuditEventType.REJECTED, actor, reason);
        return buildState(pi, reconciliation);
    }

    @Transactional(readOnly = true)
    public ApprovalStateResponse getApprovalState(UUID proformaInvoiceId) {
        ProformaInvoice pi = findOwnProformaInvoice(proformaInvoiceId);
        return buildState(pi, latestReconciliation(proformaInvoiceId));
    }

    // --- the core rule ---

    /**
     * The 2-of-N gate applies iff <strong>any</strong> matched line is a price
     * increase beyond tolerance — the conservative reading confirmed for the
     * whole-PI rule: a PI containing a price rise shouldn't escape the gate
     * because another line fell. "Increase" is the recorded sign of the 5.3
     * variance; "beyond tolerance" compares its magnitude against the tolerance
     * 5.4 recorded on the reconciliation.
     */
    private boolean requiresSignOff(Reconciliation reconciliation) {
        BigDecimal tolerance = reconciliation.getToleranceApplied();
        return reconciliationLinesFor(reconciliation.getId()).stream()
            .filter(line -> line.getFindingType() == ReconciliationFindingType.MATCHED)
            .filter(line -> line.getUnitPriceVariancePct() != null)
            .anyMatch(line -> line.getUnitPriceVariancePct().signum() > 0
                && (tolerance == null || line.getUnitPriceVariancePct().abs().compareTo(tolerance) >= 0));
    }

    // --- state assembly ---

    private ApprovalStateResponse buildState(ProformaInvoice pi, Reconciliation reconciliation) {
        boolean requiresSignOff = requiresSignOff(reconciliation);
        int required = requiredApprovals(requiresSignOff);

        List<ApprovalAction> actions = approvalActionRepository.findAll().stream()
            .filter(a -> a.getProformaInvoiceId().equals(pi.getId()))
            .sorted(Comparator.comparing(ApprovalAction::getCreatedAt))
            .toList();

        Set<UUID> distinctApprovers = actions.stream()
            .filter(a -> a.getActionType() == ApprovalActionType.APPROVE)
            .map(ApprovalAction::getActorUserId)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        int collected = distinctApprovers.size();
        boolean met = collected >= required;
        int remaining = Math.max(0, required - collected);

        boolean approvable = true;
        String blockedReason = null;
        int eligiblePoolSize = 0;
        if (requiresSignOff) {
            Set<UUID> eligible = approverPoolService.resolveEligibleApprovers();
            eligiblePoolSize = eligible.size();
            Set<UUID> reachable = new LinkedHashSet<>(distinctApprovers);
            reachable.addAll(eligible);
            if (!met && reachable.size() < required) {
                approvable = false;
                blockedReason = "Insufficient active approver-pool members: " + required
                    + " distinct sign-offs required, only " + reachable.size()
                    + " reachable with the current pool — an admin must add eligible approvers";
            }
        }

        List<ApprovalActionResponse> actionResponses = actions.stream()
            .map(a -> new ApprovalActionResponse(a.getActorUserId(), a.getActionType(), a.getComment(), a.getCreatedAt()))
            .toList();

        return new ApprovalStateResponse(
            pi.getId(), pi.getStatus(), requiresSignOff, required, collected, remaining, met,
            approvable, blockedReason, eligiblePoolSize, List.copyOf(distinctApprovers), actionResponses);
    }

    private int requiredApprovals(boolean requiresSignOff) {
        return requiresSignOff ? approverPoolService.requiredSignOffCount() : 1;
    }

    // --- guards & lookups ---

    private void assertNotSelfApproval(ProformaInvoice pi, UUID actor) {
        UUID poCreator = purchaseOrderService.getCreatedBy(pi.getPurchaseOrderId());
        if (actor.equals(pi.getLoggedBy()) || actor.equals(poCreator)) {
            throw new ConflictException(ErrorCode.SELF_APPROVAL_FORBIDDEN,
                "You cannot approve a PI on a PO you raised or logged — a second person must sign off");
        }
    }

    private boolean hasAlreadyApproved(UUID proformaInvoiceId, UUID actor) {
        return distinctApprovers(proformaInvoiceId).contains(actor);
    }

    private Set<UUID> distinctApprovers(UUID proformaInvoiceId) {
        return approvalActionRepository.findAll().stream()
            .filter(a -> a.getProformaInvoiceId().equals(proformaInvoiceId))
            .filter(a -> a.getActionType() == ApprovalActionType.APPROVE)
            .map(ApprovalAction::getActorUserId)
            .collect(Collectors.toSet());
    }

    private ProformaInvoice findAwaitingApproval(UUID proformaInvoiceId) {
        ProformaInvoice pi = findOwnProformaInvoice(proformaInvoiceId);
        if (pi.getStatus() != ProformaInvoiceStatus.ROUTED_FOR_APPROVAL) {
            throw new ConflictException(ErrorCode.PI_NOT_AWAITING_APPROVAL,
                "Proforma invoice is not awaiting approval (status " + pi.getStatus() + ")");
        }
        return pi;
    }

    private ProformaInvoice findOwnProformaInvoice(UUID id) {
        ProformaInvoice pi = proformaInvoiceRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Proforma invoice not found"));
        TenantGuard.assertOwned(pi);
        return pi;
    }

    private Reconciliation latestReconciliation(UUID proformaInvoiceId) {
        return reconciliationRepository.findAll().stream()
            .filter(r -> r.getProformaInvoiceId().equals(proformaInvoiceId))
            .max(Comparator.comparing(Reconciliation::getCreatedAt))
            .orElseThrow(() -> new NotFoundException("No reconciliation recorded for this proforma invoice"));
    }

    private List<ReconciliationLine> reconciliationLinesFor(UUID reconciliationId) {
        return reconciliationLineRepository.findAll().stream()
            .filter(line -> line.getReconciliationId().equals(reconciliationId))
            .toList();
    }
}
