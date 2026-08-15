package com.shvoy.payments.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.payments.dto.InvoiceResponse;
import com.shvoy.payments.dto.LogInvoiceRequest;
import com.shvoy.payments.service.InvoiceService;

/**
 * Story 6.4 — log a supplier's final invoice against a PO, and read it back.
 * No {@code {companyId}} path segment — the caller's company comes from {@code
 * TenantContext}.
 *
 * Logging is {@code PURCHASING}/{@code FINANCE}/{@code ADMIN}: invoices arrive
 * in a finance workflow, but Purchasing handles supplier documents, so both are
 * allowed rather than guessing the office reality. Reads are open to any
 * authenticated company user.
 */
@RestController
class InvoiceController {

    private final InvoiceService invoiceService;

    InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @PostMapping("/api/purchase-orders/{poId}/invoices")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING', 'FINANCE')")
    ResponseEntity<InvoiceResponse> log(@PathVariable UUID poId, @Valid @RequestBody LogInvoiceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invoiceService.log(poId, request));
    }

    @GetMapping("/api/purchase-orders/{poId}/invoices")
    List<InvoiceResponse> listForPurchaseOrder(@PathVariable UUID poId) {
        return invoiceService.listForPurchaseOrder(poId);
    }

    @GetMapping("/api/invoices/{id}")
    InvoiceResponse get(@PathVariable UUID id) {
        return invoiceService.get(id);
    }
}
