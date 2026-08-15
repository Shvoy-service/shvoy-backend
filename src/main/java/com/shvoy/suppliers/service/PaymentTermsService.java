package com.shvoy.suppliers.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.CurrentUserContext;
import com.shvoy.ErrorCode;
import com.shvoy.Money;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.suppliers.domain.AnchorEvent;
import com.shvoy.suppliers.domain.PaymentSplit;
import com.shvoy.suppliers.domain.PaymentTerms;
import com.shvoy.suppliers.domain.PaymentTermsType;
import com.shvoy.suppliers.domain.Supplier;
import com.shvoy.suppliers.domain.SupplierAuditEvent;
import com.shvoy.suppliers.domain.SupplierAuditEventType;
import com.shvoy.suppliers.dto.PaymentScheduleTerms;
import com.shvoy.suppliers.dto.PaymentTermsRequest;
import com.shvoy.suppliers.dto.PaymentTermsResponse;
import com.shvoy.suppliers.dto.SupplierPaymentTermsResponse;
import com.shvoy.suppliers.repository.PaymentTermsRepository;
import com.shvoy.suppliers.repository.SupplierAuditEventRepository;
import com.shvoy.suppliers.repository.SupplierRepository;

/**
 * The supplier's payment terms (supplier remodel) — typed current/target term
 * records. {@code @NamedInterface}: {@link #trySplit} (4.3) and {@link
 * #getScheduleTerms} (6.2) are the cross-module surfaces, and they now resolve
 * the supplier's <strong>current</strong> term — <em>the callers don't change</em>
 * (this resolver is the isolation point the whole remodel lands behind).
 */
@NamedInterface("payment-terms")
@Service
public class PaymentTermsService {

    private final PaymentTermsRepository paymentTermsRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierAuditEventRepository auditRepository;

    PaymentTermsService(PaymentTermsRepository paymentTermsRepository, SupplierRepository supplierRepository,
            SupplierAuditEventRepository auditRepository) {
        this.paymentTermsRepository = paymentTermsRepository;
        this.supplierRepository = supplierRepository;
        this.auditRepository = auditRepository;
    }

    /** Set/replace the current term (the required slot). */
    @Transactional
    public SupplierPaymentTermsResponse setCurrentTerm(UUID supplierId, PaymentTermsRequest request) {
        Supplier supplier = findOwnSupplier(supplierId);
        assertConsistent(request);
        PaymentTerms term = upsert(supplier.getCurrentTermId(), supplierId, request);
        supplier.setCurrentTerm(term.getId());
        supplierRepository.save(supplier);
        return getTerms(supplierId);
    }

    /** Set/replace the target term — held mid-transition until activated. */
    @Transactional
    public SupplierPaymentTermsResponse setTargetTerm(UUID supplierId, PaymentTermsRequest request) {
        Supplier supplier = findOwnSupplier(supplierId);
        assertConsistent(request);
        PaymentTerms term = upsert(supplier.getTargetTermId(), supplierId, request);
        supplier.setTargetTerm(term.getId());
        supplierRepository.save(supplier);
        return getTerms(supplierId);
    }

    /** Promote target → current (ADMIN/FINANCE) — the old current is retained historically. Audited. */
    @Transactional
    public SupplierPaymentTermsResponse activateTarget(UUID supplierId) {
        Supplier supplier = findOwnSupplier(supplierId);
        if (supplier.getTargetTermId() == null) {
            throw new ConflictException(ErrorCode.NO_TARGET_TERM, "Supplier has no target term to activate");
        }
        UUID previousCurrent = supplier.activateTarget();
        supplierRepository.save(supplier);
        audit(supplier, SupplierAuditEventType.TERMS_TARGET_ACTIVATED,
            "Target term " + supplier.getCurrentTermId() + " activated (previous current " + previousCurrent
                + " retained historically)");
        return getTerms(supplierId);
    }

    @Transactional(readOnly = true)
    public SupplierPaymentTermsResponse getTerms(UUID supplierId) {
        Supplier supplier = findOwnSupplier(supplierId);
        return new SupplierPaymentTermsResponse(
            termResponse(supplier.getCurrentTermId()), termResponse(supplier.getTargetTermId()));
    }

    /** Story 4.3's cross-module surface — the current term's deposit/balance split, or empty if no current term. */
    @Transactional(readOnly = true)
    public Optional<PaymentSplit> trySplit(UUID supplierId, Money total) {
        return currentTerm(supplierId).map(term -> term.split(total));
    }

    /** Story 6.2's cross-module surface — the current term's anchor + signed offset, or empty if no current term. */
    @Transactional(readOnly = true)
    public Optional<PaymentScheduleTerms> getScheduleTerms(UUID supplierId) {
        return currentTerm(supplierId)
            .map(term -> new PaymentScheduleTerms(term.getAnchorDateType(), term.getDaysFromAnchor()));
    }

    /**
     * The 6.5 re-spec's cross-module surface — the current term's <em>type</em>, which
     * decides what a match verdict <em>does</em> (per-PO payment gating for
     * deposit/balance &amp; zero-deposit; record-only feeding the statement view
     * for rolling). Empty when the supplier has no current term.
     */
    @Transactional(readOnly = true)
    public Optional<PaymentTermsType> getEffectiveTermsType(UUID supplierId) {
        return currentTerm(supplierId).map(PaymentTerms::getTermsType);
    }

    // --- internals ---

    private Optional<PaymentTerms> currentTerm(UUID supplierId) {
        return supplierRepository.findById(supplierId)
            .map(Supplier::getCurrentTermId)
            .flatMap(termId -> termId == null ? Optional.empty() : paymentTermsRepository.findById(termId));
    }

    private PaymentTerms upsert(UUID existingTermId, UUID supplierId, PaymentTermsRequest request) {
        PaymentTerms term = existingTermId == null ? null
            : paymentTermsRepository.findById(existingTermId).orElse(null);
        if (term == null) {
            term = new PaymentTerms(supplierId, request.termsType(), request.depositPct(),
                request.anchorDateType(), request.daysFromAnchor());
        } else {
            term.update(request.termsType(), request.depositPct(), request.anchorDateType(), request.daysFromAnchor());
        }
        return paymentTermsRepository.save(term);
    }

    /**
     * Type-consistency (confirmed rules): {@code DEPOSIT_BALANCE} needs a deposit
     * strictly between 0 and 100 (an explicit 0 is {@code ZERO_DEPOSIT}); {@code
     * ZERO_DEPOSIT}/{@code ROLLING} need a null deposit; a {@code STATEMENT_DATE}
     * anchor is coherent only for {@code ROLLING}.
     */
    private static void assertConsistent(PaymentTermsRequest request) {
        BigDecimal pct = request.depositPct();
        if (request.termsType() == PaymentTermsType.DEPOSIT_BALANCE) {
            if (pct == null || pct.signum() <= 0 || pct.compareTo(BigDecimal.valueOf(100)) >= 0) {
                throw new ConflictException(ErrorCode.INVALID_TERMS_COMBINATION,
                    "DEPOSIT_BALANCE requires a deposit_pct strictly between 0 and 100");
            }
        } else if (pct != null) {
            throw new ConflictException(ErrorCode.INVALID_TERMS_COMBINATION,
                request.termsType() + " must have no deposit_pct (an explicit 0 deposit is ZERO_DEPOSIT)");
        }
        if (request.anchorDateType() == AnchorEvent.STATEMENT_DATE
                && request.termsType() != PaymentTermsType.ROLLING) {
            throw new ConflictException(ErrorCode.INVALID_TERMS_COMBINATION,
                "STATEMENT_DATE anchor is only coherent for ROLLING terms");
        }
    }

    private PaymentTermsResponse termResponse(UUID termId) {
        if (termId == null) {
            return null;
        }
        return paymentTermsRepository.findById(termId).map(PaymentTermsService::toResponse).orElse(null);
    }

    private Supplier findOwnSupplier(UUID id) {
        Supplier supplier = supplierRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Supplier not found"));
        TenantGuard.assertOwned(supplier);
        return supplier;
    }

    private void audit(Supplier supplier, SupplierAuditEventType type, String detail) {
        auditRepository.save(new SupplierAuditEvent(supplier.getId(), type, detail, CurrentUserContext.getOrNull()));
    }

    private static PaymentTermsResponse toResponse(PaymentTerms terms) {
        return new PaymentTermsResponse(terms.getId(), terms.getSupplierId(), terms.getTermsType(),
            terms.getDepositPct(), terms.getAnchorDateType(), terms.getDaysFromAnchor(),
            terms.getCreatedAt(), terms.getUpdatedAt());
    }
}
