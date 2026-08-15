package com.shvoy.shipments.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shvoy.shipments.dto.AttachPurchaseOrderRequest;
import com.shvoy.shipments.dto.ConsignmentSummaryResponse;
import com.shvoy.shipments.service.ShipmentConsignmentService;

/**
 * Story 7.3 — co-loaded containers. A shipment is identified by its id (read
 * from the PO's shipment view, {@code GET /api/purchase-orders/{poId}/shipment}).
 * Attach/detach are {@code PURCHASING}/{@code ADMIN}; the listing is open to any
 * company user. All operations are tenant-scoped — attaching a PO from another
 * company 404s even when the shipment id is known.
 */
@RestController
@RequestMapping("/api/shipments/{shipmentId}/consignments")
class ShipmentController {

    private final ShipmentConsignmentService shipmentConsignmentService;

    ShipmentController(ShipmentConsignmentService shipmentConsignmentService) {
        this.shipmentConsignmentService = shipmentConsignmentService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    ResponseEntity<List<ConsignmentSummaryResponse>> attach(
            @PathVariable UUID shipmentId, @Valid @RequestBody AttachPurchaseOrderRequest request) {
        List<ConsignmentSummaryResponse> consignments =
            shipmentConsignmentService.attach(shipmentId, request.purchaseOrderId());
        return ResponseEntity.status(HttpStatus.CREATED).body(consignments);
    }

    @DeleteMapping("/{purchaseOrderId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    List<ConsignmentSummaryResponse> detach(
            @PathVariable UUID shipmentId, @PathVariable UUID purchaseOrderId) {
        return shipmentConsignmentService.detach(shipmentId, purchaseOrderId);
    }

    @GetMapping
    List<ConsignmentSummaryResponse> list(@PathVariable UUID shipmentId) {
        return shipmentConsignmentService.listConsignments(shipmentId);
    }
}
