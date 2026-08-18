package com.shvoy.shipments.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.shipments.dto.EtdResponse;
import com.shvoy.shipments.dto.SetConfirmedEtdRequest;
import com.shvoy.shipments.service.EtdService;

/**
 * Story 7.5 — ETD tracking. Record the supplier's confirmed ETD against the PO's
 * requested ETD and read back the delta + history. Per consignment, addressed by
 * its PO, same as the other shipment sub-resources. {@code PURCHASING}/{@code ADMIN}
 * to set; read open — a Purchasing user should see the slip on their PO screen
 * (the frontend composes this read there, the same way it composes the running
 * position / receipt rollup).
 *
 * <p>ETD is not an anchor — no payment interaction, by design.
 */
@RestController
@RequestMapping("/api/purchase-orders/{purchaseOrderId}/shipment/etd")
class EtdController {

    private final EtdService etdService;

    EtdController(EtdService etdService) {
        this.etdService = etdService;
    }

    /** Set or revise the confirmed ETD (first-touch-creates the shipment record if none exists, pre-arrival only). */
    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    EtdResponse setConfirmedEtd(@PathVariable UUID purchaseOrderId,
            @Valid @RequestBody SetConfirmedEtdRequest request) {
        return etdService.setConfirmedEtd(purchaseOrderId, request);
    }

    @GetMapping
    EtdResponse get(@PathVariable UUID purchaseOrderId) {
        return etdService.getEtd(purchaseOrderId);
    }
}
