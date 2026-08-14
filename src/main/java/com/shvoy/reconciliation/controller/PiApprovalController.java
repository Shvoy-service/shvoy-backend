package com.shvoy.reconciliation.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.reconciliation.dto.ApprovalStateResponse;
import com.shvoy.reconciliation.dto.ApproveProformaInvoiceRequest;
import com.shvoy.reconciliation.dto.RejectProformaInvoiceRequest;
import com.shvoy.reconciliation.service.PiApprovalService;

/**
 * Story 5.5 — approve / reject a routed PI, and read the approval progress.
 * No {@code {companyId}} path segment — the caller's company comes from
 * {@code TenantContext}, same as every other controller here.
 *
 * Approve/reject require the {@code APPROVER} role (the single-approver path's
 * whole eligibility rule; the 2-of-N pool-membership check is layered on in
 * the service for a price increase). A non-approver gets {@code FORBIDDEN} from
 * this guard rather than a service-level code. The approval-state read is open
 * to any authenticated company user, so anyone can see who still needs to sign
 * off.
 */
@RestController
class PiApprovalController {

    private final PiApprovalService piApprovalService;

    PiApprovalController(PiApprovalService piApprovalService) {
        this.piApprovalService = piApprovalService;
    }

    @PostMapping("/api/proforma-invoices/{piId}/approvals")
    @PreAuthorize("hasRole('APPROVER')")
    ApprovalStateResponse approve(@PathVariable UUID piId,
            @Valid @RequestBody(required = false) ApproveProformaInvoiceRequest request) {
        String comment = request == null ? null : request.comment();
        return piApprovalService.approve(piId, comment);
    }

    @PostMapping("/api/proforma-invoices/{piId}/rejections")
    @PreAuthorize("hasRole('APPROVER')")
    ApprovalStateResponse reject(@PathVariable UUID piId,
            @Valid @RequestBody RejectProformaInvoiceRequest request) {
        return piApprovalService.reject(piId, request.reason());
    }

    @GetMapping("/api/proforma-invoices/{piId}/approval-state")
    ApprovalStateResponse getApprovalState(@PathVariable UUID piId) {
        return piApprovalService.getApprovalState(piId);
    }
}
