package com.shvoy.purchaseorders.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.purchaseorders.domain.PurchaseOrderStatus;
import com.shvoy.purchaseorders.dto.CreatePurchaseOrderRequest;
import com.shvoy.purchaseorders.dto.GeneratePurchaseOrderRequest;
import com.shvoy.purchaseorders.dto.PurchaseOrderLineRequest;
import com.shvoy.purchaseorders.dto.PurchaseOrderResponse;
import com.shvoy.purchaseorders.dto.UpdateRequestedEtdRequest;
import com.shvoy.purchaseorders.service.PurchaseOrderGenerationService;
import com.shvoy.purchaseorders.service.PurchaseOrderLineService;
import com.shvoy.purchaseorders.service.PurchaseOrderService;

/**
 * Stories 4.4/4.6 — draft PO creation/editing plus finalisation/document
 * retrieval. Reads are open to any authenticated company user; every
 * mutation (including generation) is restricted to ADMIN/PURCHASING and
 * DRAFT-only (enforced in the service layer via {@code PO_NOT_EDITABLE}).
 *
 * No {@code {companyId}} path segment — same reasoning as SupplierController:
 * the caller's company always comes from TenantContext, never the URL.
 */
@RestController
@RequestMapping("/api/purchase-orders")
class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;
    private final PurchaseOrderLineService purchaseOrderLineService;
    private final PurchaseOrderGenerationService purchaseOrderGenerationService;

    PurchaseOrderController(PurchaseOrderService purchaseOrderService, PurchaseOrderLineService purchaseOrderLineService,
            PurchaseOrderGenerationService purchaseOrderGenerationService) {
        this.purchaseOrderService = purchaseOrderService;
        this.purchaseOrderLineService = purchaseOrderLineService;
        this.purchaseOrderGenerationService = purchaseOrderGenerationService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    ResponseEntity<PurchaseOrderResponse> create(@Valid @RequestBody CreatePurchaseOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(purchaseOrderService.create(request));
    }

    /** Unfiltered when {@code status} is omitted. No pagination — same pilot-scale call as SupplierController#list. */
    @GetMapping
    List<PurchaseOrderResponse> list(@RequestParam(required = false) PurchaseOrderStatus status) {
        return purchaseOrderService.list(status);
    }

    @GetMapping("/{id}")
    PurchaseOrderResponse get(@PathVariable UUID id) {
        return purchaseOrderService.get(id);
    }

    @PutMapping("/{id}/etd")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    PurchaseOrderResponse setRequestedEtd(@PathVariable UUID id, @Valid @RequestBody UpdateRequestedEtdRequest request) {
        return purchaseOrderService.setRequestedEtd(id, request);
    }

    /** 200 with the cancelled PO, not 204 — same reasoning as SupplierController#deactivate: no follow-up GET needed. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    PurchaseOrderResponse cancel(@PathVariable UUID id) {
        return purchaseOrderService.cancel(id);
    }

    @PostMapping("/{id}/lines")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    ResponseEntity<PurchaseOrderResponse> addLine(@PathVariable UUID id, @Valid @RequestBody PurchaseOrderLineRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(purchaseOrderLineService.addLine(id, request));
    }

    @PutMapping("/{id}/lines/{lineId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    PurchaseOrderResponse updateLine(@PathVariable UUID id, @PathVariable UUID lineId,
            @Valid @RequestBody PurchaseOrderLineRequest request) {
        return purchaseOrderLineService.updateLine(id, lineId, request);
    }

    @DeleteMapping("/{id}/lines/{lineId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    PurchaseOrderResponse removeLine(@PathVariable UUID id, @PathVariable UUID lineId) {
        return purchaseOrderLineService.removeLine(id, lineId);
    }

    /**
     * Story 4.6. {@code request} (and its {@code override}) is optional —
     * omitted entirely for a clean draft with nothing to override. See
     * {@code PurchaseOrderGenerationService#generate} for the full
     * precondition/gate/snapshot sequence this triggers.
     */
    @PostMapping("/{id}/generate")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    PurchaseOrderResponse generate(@PathVariable UUID id, @RequestBody(required = false) GeneratePurchaseOrderRequest request) {
        return purchaseOrderGenerationService.generate(id, request);
    }

    /** Open to any authenticated role, same as {@link #get} — reading the generated document isn't a mutation. */
    @GetMapping("/{id}/document")
    ResponseEntity<byte[]> document(@PathVariable UUID id) {
        byte[] pdf = purchaseOrderGenerationService.getDocument(id);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"purchase-order.pdf\"")
            .body(pdf);
    }
}
