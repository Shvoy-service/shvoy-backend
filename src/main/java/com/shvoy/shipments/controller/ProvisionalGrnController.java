package com.shvoy.shipments.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shvoy.shipments.dto.AmendGoodsReceiptRequest;
import com.shvoy.shipments.dto.GoodsReceiptResponse;
import com.shvoy.shipments.service.ProvisionalGrnService;

/**
 * Story 7.4 — the provisional GRN for a PO's consignment. Creation is an
 * <strong>explicit</strong> {@code PURCHASING}/{@code ADMIN} action (not
 * automatic on document upload), gated by the document predicate; it certifies
 * the documents are in and correct enough to receipt, and feeds the three-way
 * match. Amendment (pre-arrival, reason required) is audited. The read is open
 * — it's the "does a GRN exist, and what quantities" shape 6.5 reasons about.
 */
@RestController
@RequestMapping("/api/purchase-orders/{purchaseOrderId}/shipment/provisional-grn")
class ProvisionalGrnController {

    private final ProvisionalGrnService provisionalGrnService;

    ProvisionalGrnController(ProvisionalGrnService provisionalGrnService) {
        this.provisionalGrnService = provisionalGrnService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    ResponseEntity<GoodsReceiptResponse> create(@PathVariable UUID purchaseOrderId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(provisionalGrnService.create(purchaseOrderId));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    GoodsReceiptResponse amend(@PathVariable UUID purchaseOrderId,
            @Valid @RequestBody AmendGoodsReceiptRequest request) {
        return provisionalGrnService.amend(purchaseOrderId, request);
    }

    @GetMapping
    GoodsReceiptResponse get(@PathVariable UUID purchaseOrderId) {
        return provisionalGrnService.getForPurchaseOrder(purchaseOrderId);
    }
}
