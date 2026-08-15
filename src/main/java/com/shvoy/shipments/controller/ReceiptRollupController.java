package com.shvoy.shipments.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.shipments.dto.CloseShortRequest;
import com.shvoy.shipments.dto.ReceiptRollupResponse;
import com.shvoy.shipments.service.ReceiptRollupService;

/**
 * Receipt rollup &amp; PO closure — the per-PO cumulative-receipt view, and the
 * Finance/Admin close-short escape valve. In {@code shipments} (the receipt
 * side owns the cumulative operation); auto-closure needs no endpoint — it's
 * observed from receipt events, not commanded.
 */
@RestController
class ReceiptRollupController {

    private final ReceiptRollupService receiptRollupService;

    ReceiptRollupController(ReceiptRollupService receiptRollupService) {
        this.receiptRollupService = receiptRollupService;
    }

    /** The cumulative-receipt rollup (per-SKU ordered vs received, valued; complete/over-delivered). Open read. */
    @GetMapping("/api/purchase-orders/{poId}/receipt-rollup")
    ReceiptRollupResponse rollup(@PathVariable UUID poId) {
        return receiptRollupService.getRollup(poId);
    }

    /**
     * Close short — a Finance/Admin write-off of an undelivered remainder.
     * {@code FINANCE}/{@code ADMIN} only (closing short writes off expected goods —
     * same segregation as the 6.6 override); a required reason; the outstanding
     * value is surfaced in the returned rollup and recorded on the audit.
     */
    @PostMapping("/api/purchase-orders/{poId}/close-short")
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
    ResponseEntity<ReceiptRollupResponse> closeShort(@PathVariable UUID poId,
            @Valid @RequestBody CloseShortRequest request) {
        receiptRollupService.closeShort(poId, request.reason());
        return ResponseEntity.ok(receiptRollupService.getRollup(poId));
    }
}
