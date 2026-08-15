package com.shvoy.payments.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.CurrentUserContext;
import com.shvoy.Money;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.ValidationException;
import com.shvoy.payments.domain.CreditCause;
import com.shvoy.payments.domain.CreditLedgerAuditEventType;
import com.shvoy.payments.domain.CreditLedgerEntry;
import com.shvoy.payments.domain.CreditLedgerStatus;
import com.shvoy.payments.dto.CreditLedgerEntryResponse;
import com.shvoy.payments.dto.CreditMatchOutcome;
import com.shvoy.payments.dto.CreditMatchResult;
import com.shvoy.payments.dto.LogCreditRequest;
import com.shvoy.payments.repository.CreditLedgerEntryRepository;
import com.shvoy.purchaseorders.service.PurchaseOrderService;

/**
 * The open-credit ledger (Story 6.7) — the durable memory of shortfalls,
 * damage, and agreed deductions, and the rule they exist to enforce: an invoice
 * claiming a prior credit is only correct if it matches an OPEN entry here.
 *
 * <p>Three future writers ({@link #apply} at match time — 6.5; mismatch
 * resolution — 6.6; the NCR flow) go through this one service, so its API is a
 * clean interface even though it lives in {@code payments}. The match-check
 * ({@link #checkClaim}) only <em>answers</em>; applying is the separate {@link
 * #apply}, so a check never mutates the ledger.
 */
@Service
public class CreditLedgerService {

    private final CreditLedgerEntryRepository creditLedgerEntryRepository;
    private final PurchaseOrderService purchaseOrderService;
    private final CreditLedgerAuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    CreditLedgerService(CreditLedgerEntryRepository creditLedgerEntryRepository,
            PurchaseOrderService purchaseOrderService, CreditLedgerAuditService auditService,
            ApplicationEventPublisher eventPublisher) {
        this.creditLedgerEntryRepository = creditLedgerEntryRepository;
        this.purchaseOrderService = purchaseOrderService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public CreditLedgerEntryResponse log(LogCreditRequest request) {
        purchaseOrderService.assertOwnPurchaseOrderExists(request.purchaseOrderId());
        Money amount = buildAmount(request.amount(), request.currency());
        if (request.cause() == CreditCause.OTHER && isBlank(request.causeDetail())) {
            throw new ValidationException("causeDetail is required when cause is OTHER");
        }
        UUID actor = CurrentUserContext.get();

        CreditLedgerEntry entry = creditLedgerEntryRepository.save(new CreditLedgerEntry(
            request.purchaseOrderId(), amount, request.cause(), request.causeDetail(),
            request.ncrReference(), request.targetInvoiceId(), actor));
        auditService.record(entry.getId(), CreditLedgerAuditEventType.LOGGED, actor,
            "Logged " + request.cause() + " credit of " + amount.amount() + " " + amount.currency());
        // A new open credit may satisfy an invoice's claim that previously blocked the match (6.5).
        eventPublisher.publishEvent(new MatchInputChangedEvent(request.purchaseOrderId()));
        return toResponse(entry);
    }

    @Transactional(readOnly = true)
    public CreditLedgerEntryResponse get(UUID id) {
        return toResponse(findOwn(id));
    }

    /** Default (status null) lists OPEN entries — the operational view; a supplied status/PO narrows it. Newest first. */
    @Transactional(readOnly = true)
    public List<CreditLedgerEntryResponse> list(CreditLedgerStatus status, UUID purchaseOrderId) {
        CreditLedgerStatus effectiveStatus = status != null ? status : CreditLedgerStatus.OPEN;
        return creditLedgerEntryRepository.findAll().stream()
            .filter(entry -> entry.getStatus() == effectiveStatus)
            .filter(entry -> purchaseOrderId == null || entry.getPurchaseOrderId().equals(purchaseOrderId))
            .sorted(Comparator.comparing(CreditLedgerEntry::getCreatedAt).reversed())
            .map(CreditLedgerService::toResponse)
            .toList();
    }

    /** The "Open discrepancies: N" dashboard stat — the third Screen-1 stat 6.3 left stubbed. */
    @Transactional(readOnly = true)
    public long openCount() {
        return creditLedgerEntryRepository.findAll().stream()
            .filter(entry -> entry.getStatus() == CreditLedgerStatus.OPEN)
            .count();
    }

    /**
     * The ledger's whole purpose (Story 6.7's matching rule) — does {@code
     * claimedAmount} on {@code purchaseOrderId} match an OPEN entry? A match is
     * the same PO, {@code OPEN} status, and the <strong>exact</strong> amount
     * (2dp + currency; credits are agreed figures, no tolerance). Read-only —
     * 6.5 calls {@link #apply} on a match.
     */
    @Transactional(readOnly = true)
    public CreditMatchResult checkClaim(UUID purchaseOrderId, Money claimedAmount) {
        List<CreditLedgerEntry> openForPo = creditLedgerEntryRepository.findAll().stream()
            .filter(entry -> entry.getStatus() == CreditLedgerStatus.OPEN)
            .filter(entry -> entry.getPurchaseOrderId().equals(purchaseOrderId))
            .toList();

        Optional<CreditLedgerEntry> exact = openForPo.stream()
            .filter(entry -> amountsEqual(entry.getAmount(), claimedAmount))
            .findFirst();
        if (exact.isPresent()) {
            return new CreditMatchResult(true, exact.get().getId(), CreditMatchOutcome.MATCHED);
        }
        return new CreditMatchResult(false, null,
            openForPo.isEmpty() ? CreditMatchOutcome.NO_OPEN_CREDIT : CreditMatchOutcome.AMOUNT_MISMATCH);
    }

    /**
     * Applies an OPEN entry against the invoice that claimed it — {@code OPEN →
     * APPLIED}, once. Called by 6.5 after a successful {@link #checkClaim}; the
     * entity rejects a second application ({@code CREDIT_NOT_OPEN}).
     */
    @Transactional
    public CreditLedgerEntryResponse apply(UUID id, UUID invoiceId) {
        CreditLedgerEntry entry = findOwn(id);
        entry.apply(invoiceId);
        creditLedgerEntryRepository.save(entry);
        auditService.record(entry.getId(), CreditLedgerAuditEventType.APPLIED, CurrentUserContext.get(),
            "Applied against invoice " + invoiceId);
        return toResponse(entry);
    }

    /**
     * Whether the claim is <em>already satisfied</em> by an entry this same
     * invoice previously applied — the re-entrancy guard for the three-way match
     * (6.5). A passing match applies the OPEN entry ({@code OPEN → APPLIED}); if
     * the match then re-runs (a GRN amendment, a re-confirmed PI), {@link
     * #checkClaim} would no longer see an OPEN entry and the claim would
     * spuriously look unmatched. This confirms the credit was legitimately
     * consumed by this very invoice, so the re-run stays a pass and never
     * re-applies. Read-only.
     */
    @Transactional(readOnly = true)
    public boolean claimAlreadyAppliedToInvoice(UUID purchaseOrderId, Money claimedAmount, UUID invoiceId) {
        return creditLedgerEntryRepository.findAll().stream()
            .filter(entry -> entry.getStatus() == CreditLedgerStatus.APPLIED)
            .filter(entry -> entry.getPurchaseOrderId().equals(purchaseOrderId))
            .filter(entry -> invoiceId.equals(entry.getTargetInvoiceId()))
            .anyMatch(entry -> amountsEqual(entry.getAmount(), claimedAmount));
    }

    @Transactional
    public CreditLedgerEntryResponse cancel(UUID id, String reason) {
        CreditLedgerEntry entry = findOwn(id);
        entry.cancel(reason);
        creditLedgerEntryRepository.save(entry);
        auditService.record(entry.getId(), CreditLedgerAuditEventType.CANCELLED, CurrentUserContext.get(),
            "Cancelled: " + reason);
        return toResponse(entry);
    }

    // --- internals ---

    private static boolean amountsEqual(Money a, Money b) {
        return a.currency().equals(b.currency()) && a.amount().compareTo(b.amount()) == 0;
    }

    private CreditLedgerEntry findOwn(UUID id) {
        CreditLedgerEntry entry = creditLedgerEntryRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Credit ledger entry not found"));
        TenantGuard.assertOwned(entry);
        return entry;
    }

    private static Money buildAmount(BigDecimal amount, String currency) {
        try {
            Currency.getInstance(currency);
            return new Money(amount, currency);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("currency: " + e.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static CreditLedgerEntryResponse toResponse(CreditLedgerEntry entry) {
        return new CreditLedgerEntryResponse(
            entry.getId(),
            entry.getPurchaseOrderId(),
            entry.getAmount(),
            entry.getCause(),
            entry.getCauseDetail(),
            entry.getNcrReference(),
            entry.getTargetInvoiceId(),
            entry.getStatus(),
            entry.getClosureReason(),
            entry.getLoggedBy(),
            entry.getCreatedAt(),
            entry.getUpdatedAt());
    }
}
