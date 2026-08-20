package com.shvoy.payments.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.CurrentUserContext;
import com.shvoy.ErrorCode;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.payments.domain.DiscrepancyCase;
import com.shvoy.payments.domain.DiscrepancyCaseAuditEvent;
import com.shvoy.payments.domain.DiscrepancyCaseAuditEventType;
import com.shvoy.payments.domain.DiscrepancyResolutionType;
import com.shvoy.payments.domain.Payment;
import com.shvoy.payments.dto.CreditLedgerEntryResponse;
import com.shvoy.payments.dto.DisputeDiscrepancyRequest;
import com.shvoy.payments.dto.LogCaseCreditRequest;
import com.shvoy.payments.dto.LogCreditRequest;
import com.shvoy.payments.dto.OverrideDiscrepancyRequest;
import com.shvoy.payments.repository.DiscrepancyCaseAuditEventRepository;
import com.shvoy.payments.repository.DiscrepancyCaseRepository;
import com.shvoy.payments.repository.PaymentRepository;
import com.shvoy.purchaseorders.service.PurchaseOrderService;

/**
 * The discrepancy case lifecycle (Story 6.6) — the human half of the three-way
 * match control. The match (6.5) drives {@link #onMatchBlocked} / {@link
 * #onMatchPassed}; a resolver drives {@link #claim}, {@link #logCredit} (path
 * b), {@link #override} (path c), and {@link #dispute} (path d). Path (a),
 * correcting the data, needs no method here — the owning story's fix re-triggers
 * the match, and {@code onMatchPassed} auto-resolves the case.
 *
 * <p>One active case per payment: a re-fail updates the same case, never
 * duplicates; a fail with no active case opens a new one. Every transition is
 * immutably audited.
 */
@Service
public class DiscrepancyCaseService {

    private final DiscrepancyCaseRepository caseRepository;
    private final DiscrepancyCaseAuditEventRepository auditRepository;
    private final DiscrepancyNotifier notifier;
    private final PaymentRepository paymentRepository;
    private final PurchaseOrderService purchaseOrderService;
    private final CreditLedgerService creditLedgerService;

    DiscrepancyCaseService(DiscrepancyCaseRepository caseRepository,
            DiscrepancyCaseAuditEventRepository auditRepository, DiscrepancyNotifier notifier,
            PaymentRepository paymentRepository, PurchaseOrderService purchaseOrderService,
            CreditLedgerService creditLedgerService) {
        this.caseRepository = caseRepository;
        this.auditRepository = auditRepository;
        this.notifier = notifier;
        this.paymentRepository = paymentRepository;
        this.purchaseOrderService = purchaseOrderService;
        this.creditLedgerService = creditLedgerService;
    }

    // --- driven by the match (6.5), in its transaction ---

    /** The match blocked this payment: refresh the active case's detail, or open a new one and notify the resolvers. */
    void onMatchBlocked(Payment payment, String failureDetail) {
        Optional<DiscrepancyCase> active = activeCaseFor(payment.getId());
        if (active.isPresent()) {
            DiscrepancyCase existing = active.get();
            existing.updateDetail(failureDetail);
            caseRepository.save(existing);
            audit(existing, DiscrepancyCaseAuditEventType.DETAIL_UPDATED, failureDetail);
            return;
        }
        DiscrepancyCase created = caseRepository.save(
            new DiscrepancyCase(payment.getId(), payment.getPurchaseOrderId(), failureDetail));
        audit(created, DiscrepancyCaseAuditEventType.OPENED, failureDetail);
        notifier.notifyOpened(purchaseOrderService.getSummary(payment.getPurchaseOrderId()).poNumber(),
            failureDetail, created.getId());
    }

    /** The match passed: auto-resolve the active case — {@code CREDITED} if a credit was logged from it, else {@code CORRECTED}. */
    void onMatchPassed(Payment payment) {
        activeCaseFor(payment.getId()).ifPresent(caseEntity -> {
            DiscrepancyResolutionType type = caseEntity.getCreditLedgerEntryId() != null
                ? DiscrepancyResolutionType.CREDITED
                : DiscrepancyResolutionType.CORRECTED;
            caseEntity.resolve(type, CurrentUserContext.getOrNull(), null);
            caseRepository.save(caseEntity);
            audit(caseEntity, DiscrepancyCaseAuditEventType.RESOLVED, "Match passed — resolved (" + type + ")");
        });
    }

    // --- driven by a resolver ---

    /** Claim the case — the claimer becomes the named resolver on record (the claimable-queue model). */
    @Transactional
    public void claim(UUID caseId) {
        DiscrepancyCase caseEntity = findActiveOwnCase(caseId);
        caseEntity.claim(CurrentUserContext.get());
        caseRepository.save(caseEntity);
        audit(caseEntity, DiscrepancyCaseAuditEventType.CLAIMED, "Claimed by " + CurrentUserContext.get());
    }

    /**
     * Path (b): log a credit in the ledger from this case, linking case → entry.
     * The case does <em>not</em> resolve now — it resolves when the match passes
     * once a claiming/reduced invoice aligns (the credit log re-triggers the
     * match; {@link #onMatchPassed} closes the case as {@code CREDITED}).
     */
    @Transactional
    public CreditLedgerEntryResponse logCredit(UUID caseId, LogCaseCreditRequest request) {
        DiscrepancyCase caseEntity = findActiveOwnCase(caseId);
        CreditLedgerEntryResponse entry = creditLedgerService.log(new LogCreditRequest(
            caseEntity.getPurchaseOrderId(), request.amount(), request.currency(), request.cause(),
            request.causeDetail(), request.ncrReference(), null));
        caseEntity.linkCredit(entry.id());
        caseRepository.save(caseEntity);
        audit(caseEntity, DiscrepancyCaseAuditEventType.CREDIT_LOGGED,
            "Logged " + request.cause() + " credit " + entry.amount().currency() + " "
                + entry.amount().amount() + " (entry " + entry.id() + ")");
        return entry;
    }

    /**
     * Path (c): accept the difference as-is — force-pass the payment to
     * READY_TO_PAY despite the mismatch, with a required reason. FINANCE/ADMIN
     * only (enforced at the controller) — overriding the payment control is a
     * Finance-grade decision, the same segregation instinct as self-approval
     * prevention (5.5).
     */
    @Transactional
    public void override(UUID caseId, OverrideDiscrepancyRequest request) {
        DiscrepancyCase caseEntity = findActiveOwnCase(caseId);
        Payment payment = findOwnPayment(caseEntity.getPaymentId());
        payment.overrideMatch();
        paymentRepository.save(payment);
        caseEntity.resolve(DiscrepancyResolutionType.OVERRIDDEN, CurrentUserContext.get(), request.reason());
        caseRepository.save(caseEntity);
        audit(caseEntity, DiscrepancyCaseAuditEventType.RESOLVED,
            "Overridden (accepted as-is) — payment forced READY_TO_PAY. Reason: " + request.reason()
                + ". Mismatch was: " + caseEntity.getFailureDetail());
    }

    /** Path (d): contest the invoice outright — the case is DISPUTED and the payment stays BLOCKED. */
    @Transactional
    public void dispute(UUID caseId, DisputeDiscrepancyRequest request) {
        DiscrepancyCase caseEntity = findActiveOwnCase(caseId);
        caseEntity.dispute(CurrentUserContext.get(), request.reason());
        caseRepository.save(caseEntity);
        audit(caseEntity, DiscrepancyCaseAuditEventType.DISPUTED, "Disputed. Reason: " + request.reason());
    }

    // --- internals ---

    private Optional<DiscrepancyCase> activeCaseFor(UUID paymentId) {
        return caseRepository.findAll().stream()
            .filter(caseEntity -> caseEntity.getPaymentId().equals(paymentId) && caseEntity.isActive())
            .findFirst();
    }

    private DiscrepancyCase findActiveOwnCase(UUID caseId) {
        DiscrepancyCase caseEntity = caseRepository.findById(caseId)
            .orElseThrow(() -> new NotFoundException("Discrepancy case not found"));
        TenantGuard.assertOwned(caseEntity);
        if (!caseEntity.isActive()) {
            throw new ConflictException(ErrorCode.DISCREPANCY_NOT_OPEN,
                "Discrepancy case is already resolved (" + caseEntity.getStatus() + ")");
        }
        return caseEntity;
    }

    private Payment findOwnPayment(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new NotFoundException("Payment not found"));
        TenantGuard.assertOwned(payment);
        return payment;
    }

    private void audit(DiscrepancyCase caseEntity, DiscrepancyCaseAuditEventType type, String detail) {
        auditRepository.save(new DiscrepancyCaseAuditEvent(caseEntity.getId(), type, detail, CurrentUserContext.getOrNull()));
    }
}
