package com.shvoy.reconciliation.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.shvoy.reconciliation.dto.ReconciliationDetailResponse;
import com.shvoy.reconciliation.dto.ReconciliationSummaryResponse;
import com.shvoy.reconciliation.service.ReconciliationQueryService;

/**
 * Story 5.7 — the consolidated reads: the full Screen 4 payload for one PI in a
 * single call, the reconciliation history for a PO (including superseded PIs),
 * and the approver's pending-approval queue. All reads, open to any
 * authenticated company user — the read-only/audit role "views everything,
 * changes nothing", and the audit trail is readable but mutable by no one
 * (there is no write path to it anywhere).
 */
@RestController
class ReconciliationQueryController {

    private final ReconciliationQueryService reconciliationQueryService;

    ReconciliationQueryController(ReconciliationQueryService reconciliationQueryService) {
        this.reconciliationQueryService = reconciliationQueryService;
    }

    @GetMapping("/api/proforma-invoices/{piId}/reconciliation-detail")
    ReconciliationDetailResponse getDetail(@PathVariable UUID piId) {
        return reconciliationQueryService.getDetail(piId);
    }

    @GetMapping("/api/purchase-orders/{poId}/reconciliations")
    List<ReconciliationSummaryResponse> listForPurchaseOrder(@PathVariable UUID poId) {
        return reconciliationQueryService.listForPurchaseOrder(poId);
    }

    @GetMapping("/api/reconciliation/pending-approval")
    List<ReconciliationSummaryResponse> listPendingApproval() {
        return reconciliationQueryService.listPendingApproval();
    }
}
