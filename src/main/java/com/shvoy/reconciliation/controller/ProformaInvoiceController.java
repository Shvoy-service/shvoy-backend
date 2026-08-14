package com.shvoy.reconciliation.controller;

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

import com.shvoy.reconciliation.dto.LogProformaInvoiceRequest;
import com.shvoy.reconciliation.dto.ProformaInvoiceResponse;
import com.shvoy.reconciliation.service.ProformaInvoiceService;

/**
 * Story 5.2 — log a supplier's confirmed PI against a PO, and read it back.
 * No {@code {companyId}} path segment — same reasoning as every other
 * controller in this codebase: the caller's company always comes from
 * {@code TenantContext}, never the URL.
 *
 * Two different URL roots on one controller ({@code
 * /purchase-orders/{poId}/proforma-invoices} and {@code
 * /proforma-invoices/{id}}, per this story's own endpoint list) rather than
 * a shared class-level {@code @RequestMapping} — each method spells out its
 * full path instead.
 */
@RestController
class ProformaInvoiceController {

    private final ProformaInvoiceService proformaInvoiceService;

    ProformaInvoiceController(ProformaInvoiceService proformaInvoiceService) {
        this.proformaInvoiceService = proformaInvoiceService;
    }

    /** Logging is PURCHASING/ADMIN-only — Purchasing "reconciles PIs" per the roles definition. */
    @PostMapping("/api/purchase-orders/{poId}/proforma-invoices")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    ResponseEntity<ProformaInvoiceResponse> log(@PathVariable UUID poId, @Valid @RequestBody LogProformaInvoiceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(proformaInvoiceService.log(poId, request));
    }

    /** Open to any authenticated company user, same as every other read in this codebase. */
    @GetMapping("/api/purchase-orders/{poId}/proforma-invoices")
    List<ProformaInvoiceResponse> listForPurchaseOrder(@PathVariable UUID poId) {
        return proformaInvoiceService.listForPurchaseOrder(poId);
    }

    @GetMapping("/api/proforma-invoices/{id}")
    ProformaInvoiceResponse get(@PathVariable UUID id) {
        return proformaInvoiceService.get(id);
    }
}
