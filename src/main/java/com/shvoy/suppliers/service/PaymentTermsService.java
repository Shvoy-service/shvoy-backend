package com.shvoy.suppliers.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.Money;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.suppliers.domain.PaymentSplit;
import com.shvoy.suppliers.domain.PaymentTerms;
import com.shvoy.suppliers.domain.Supplier;
import com.shvoy.suppliers.dto.PaymentScheduleTerms;
import com.shvoy.suppliers.dto.PaymentTermsRequest;
import com.shvoy.suppliers.dto.PaymentTermsResponse;
import com.shvoy.suppliers.repository.PaymentTermsRepository;
import com.shvoy.suppliers.repository.SupplierRepository;

/**
 * Plain {@code @Transactional}, same reasoning as SupplierService: every
 * method here runs against a tenant already established by
 * TenantContextFilter before the controller is invoked.
 *
 * {@link #trySplit} is this class's cross-module surface (Story 4.3) —
 * {@code @NamedInterface}, same pattern as {@code PriceResolutionService}
 * (3.8), so another module can get a supplier's deposit/balance split
 * without reaching into {@link PaymentTermsRepository}/{@link PaymentTerms}
 * directly, which stay internal to this module.
 */
@NamedInterface("payment-terms")
@Service
public class PaymentTermsService {

    private final PaymentTermsRepository paymentTermsRepository;
    private final SupplierRepository supplierRepository;

    PaymentTermsService(PaymentTermsRepository paymentTermsRepository, SupplierRepository supplierRepository) {
        this.paymentTermsRepository = paymentTermsRepository;
        this.supplierRepository = supplierRepository;
    }

    /**
     * Upsert: PUT sets or updates a supplier's terms with the same
     * full-representation semantics as SupplierService#update, not a
     * separate create/update pair.
     */
    @Transactional
    public PaymentTermsResponse set(UUID supplierId, PaymentTermsRequest request) {
        findOwnSupplier(supplierId);
        PaymentTerms terms = paymentTermsRepository.findById(supplierId)
            .map(existing -> {
                existing.update(request.depositPercentage(), request.anchorEvent(), request.daysOffset());
                return existing;
            })
            .orElseGet(() -> new PaymentTerms(
                supplierId, request.depositPercentage(), request.anchorEvent(), request.daysOffset()));
        return toResponse(paymentTermsRepository.save(terms));
    }

    @Transactional(readOnly = true)
    public PaymentTermsResponse get(UUID supplierId) {
        findOwnSupplier(supplierId);
        PaymentTerms terms = paymentTermsRepository.findById(supplierId)
            .orElseThrow(() -> new NotFoundException("Payment terms not set for this supplier"));
        return toResponse(terms);
    }

    /**
     * The supplier's deposit/balance split of {@code total}, or empty if
     * the supplier has no payment terms configured — never a default
     * split, since guessing a deposit percentage nobody agreed to would be
     * worse than reporting nothing. Tenant-scoping comes from
     * {@code paymentTermsRepository}'s own {@code TenantScoped} filtering,
     * same as everywhere else — no separate ownership check needed since
     * this never crosses from one company's supplier to another's terms.
     */
    @Transactional(readOnly = true)
    public Optional<PaymentSplit> trySplit(UUID supplierId, Money total) {
        return paymentTermsRepository.findById(supplierId).map(terms -> terms.split(total));
    }

    /**
     * Story 6.2's cross-module surface — the anchor event + signed offset the
     * payments module snapshots to schedule a balance's due date. Empty when
     * the supplier has no terms configured (a balance then has no calculable
     * due date). Same {@code @NamedInterface} contract as {@link #trySplit},
     * exposing only the schedule-relevant fields, never {@code PaymentTerms}.
     */
    @Transactional(readOnly = true)
    public Optional<PaymentScheduleTerms> getScheduleTerms(UUID supplierId) {
        return paymentTermsRepository.findById(supplierId)
            .map(terms -> new PaymentScheduleTerms(terms.getAnchorEvent(), terms.getDaysOffset()));
    }

    private void findOwnSupplier(UUID id) {
        Supplier supplier = supplierRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Supplier not found"));
        TenantGuard.assertOwned(supplier);
    }

    private static PaymentTermsResponse toResponse(PaymentTerms terms) {
        return new PaymentTermsResponse(terms.getSupplierId(), terms.getDepositPercentage(),
            terms.getBalancePercentage(), terms.getAnchorEvent(), terms.getDaysOffset(),
            terms.getCreatedAt(), terms.getUpdatedAt());
    }
}
