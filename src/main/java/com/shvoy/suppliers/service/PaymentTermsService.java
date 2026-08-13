package com.shvoy.suppliers.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.suppliers.domain.PaymentTerms;
import com.shvoy.suppliers.domain.Supplier;
import com.shvoy.suppliers.dto.PaymentTermsRequest;
import com.shvoy.suppliers.dto.PaymentTermsResponse;
import com.shvoy.suppliers.repository.PaymentTermsRepository;
import com.shvoy.suppliers.repository.SupplierRepository;

/**
 * Plain {@code @Transactional}, same reasoning as SupplierService: every
 * method here runs against a tenant already established by
 * TenantContextFilter before the controller is invoked.
 */
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
