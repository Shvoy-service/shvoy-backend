package com.shvoy.suppliers.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.suppliers.dto.PaymentTermsRequest;
import com.shvoy.suppliers.dto.SupplierPaymentTermsResponse;
import com.shvoy.suppliers.service.PaymentTermsService;

/**
 * A supplier's payment terms (supplier remodel) — the current term, an optional
 * target term held mid-transition, and the explicit activation that promotes
 * target → current. Setting terms is {@code PURCHASING}/{@code ADMIN};
 * activation (a terms change that takes effect) is {@code ADMIN}/{@code FINANCE}.
 */
@RestController
@RequestMapping("/api/suppliers/{supplierId}/payment-terms")
class PaymentTermsController {

    private final PaymentTermsService paymentTermsService;

    PaymentTermsController(PaymentTermsService paymentTermsService) {
        this.paymentTermsService = paymentTermsService;
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    SupplierPaymentTermsResponse setCurrent(@PathVariable UUID supplierId,
            @Valid @RequestBody PaymentTermsRequest request) {
        return paymentTermsService.setCurrentTerm(supplierId, request);
    }

    @PutMapping("/target")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    SupplierPaymentTermsResponse setTarget(@PathVariable UUID supplierId,
            @Valid @RequestBody PaymentTermsRequest request) {
        return paymentTermsService.setTargetTerm(supplierId, request);
    }

    @PostMapping("/target/activate")
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
    SupplierPaymentTermsResponse activateTarget(@PathVariable UUID supplierId) {
        return paymentTermsService.activateTarget(supplierId);
    }

    @GetMapping
    SupplierPaymentTermsResponse get(@PathVariable UUID supplierId) {
        return paymentTermsService.getTerms(supplierId);
    }
}
