package com.shvoy.containerfill.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.shvoy.containerfill.dto.CancelContainerFillOfferRequest;
import com.shvoy.containerfill.dto.ContainerFillOfferResponse;
import com.shvoy.containerfill.dto.ContainerFillOfferSummary;
import com.shvoy.containerfill.dto.FlagContainerFillOfferRequest;
import com.shvoy.containerfill.service.ContainerFillOfferService;
import com.shvoy.containerfill.service.ContainerFillOfferViewService;

/**
 * Story 8.1 — the container-fill offer endpoints. Flagging is addressed <em>by
 * PO</em> (like every shipment write), so an early offer can create the shipment
 * on first touch; the offers themselves are then a module-level resource. Reads
 * are open to any company user; mutations are PURCHASING/ADMIN.
 */
@RestController
class ContainerFillOfferController {

    private final ContainerFillOfferService offerService;
    private final ContainerFillOfferViewService viewService;

    ContainerFillOfferController(ContainerFillOfferService offerService, ContainerFillOfferViewService viewService) {
        this.offerService = offerService;
        this.viewService = viewService;
    }

    @PostMapping("/api/purchase-orders/{purchaseOrderId}/container-fill-offers")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    ResponseEntity<ContainerFillOfferResponse> flag(
            @PathVariable UUID purchaseOrderId, @Valid @RequestBody FlagContainerFillOfferRequest request) {
        UUID offerId = offerService.flag(purchaseOrderId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(viewService.getView(offerId));
    }

    @GetMapping("/api/container-fill-offers")
    List<ContainerFillOfferSummary> list(
            @RequestParam(value = "includeDecided", defaultValue = "false") boolean includeDecided) {
        return viewService.list(includeDecided);
    }

    @GetMapping("/api/container-fill-offers/{offerId}")
    ContainerFillOfferResponse get(@PathVariable UUID offerId) {
        return viewService.getView(offerId);
    }

    @PostMapping("/api/container-fill-offers/{offerId}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    ContainerFillOfferResponse cancel(
            @PathVariable UUID offerId, @Valid @RequestBody CancelContainerFillOfferRequest request) {
        offerService.cancel(offerId, request);
        return viewService.getView(offerId);
    }
}
