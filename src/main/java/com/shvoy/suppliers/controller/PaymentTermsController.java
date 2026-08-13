package com.shvoy.suppliers.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.suppliers.dto.PaymentTermsRequest;
import com.shvoy.suppliers.dto.PaymentTermsResponse;
import com.shvoy.suppliers.service.PaymentTermsService;

/**
 * Dedicated sub-resource under a supplier, not folded into
 * SupplierController/SupplierResponse — payment terms are a distinct
 * concept with their own validation and role rules, same reasoning as
 * splitting TeamController from CompanyProfileController.
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
    PaymentTermsResponse set(@PathVariable UUID supplierId, @Valid @RequestBody PaymentTermsRequest request) {
        return paymentTermsService.set(supplierId, request);
    }

    @GetMapping
    PaymentTermsResponse get(@PathVariable UUID supplierId) {
        return paymentTermsService.get(supplierId);
    }
}
