package com.shvoy.shipments.controller;

import java.util.UUID;

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

import jakarta.validation.Valid;

import com.shvoy.shipments.dto.ArrivalResponse;
import com.shvoy.shipments.dto.ConfirmArrivalRequest;
import com.shvoy.shipments.dto.CorrectArrivalDateRequest;
import com.shvoy.shipments.service.ArrivalConfirmationService;

/**
 * Story 7.6 — physical arrival confirmation, the second-stage action on a
 * consignment (Screen 5's "Stage 2"). Per consignment, addressed by its PO, same
 * as the provisional-GRN endpoints. {@code PURCHASING}/{@code ADMIN} to act;
 * reads open.
 *
 * <p>Arrival is checked against the provisional GRN, never the PO, and never
 * unwinds settled state — a count mismatch raises a discrepancy record for the
 * credit lane, it does not reopen a payment.
 */
@RestController
@RequestMapping("/api/purchase-orders/{purchaseOrderId}/shipment/arrival")
class ArrivalConfirmationController {

    private final ArrivalConfirmationService arrivalConfirmationService;

    ArrivalConfirmationController(ArrivalConfirmationService arrivalConfirmationService) {
        this.arrivalConfirmationService = arrivalConfirmationService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    ResponseEntity<ArrivalResponse> confirm(@PathVariable UUID purchaseOrderId,
            @Valid @RequestBody ConfirmArrivalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(arrivalConfirmationService.confirmArrival(purchaseOrderId, request));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    ArrivalResponse correctDate(@PathVariable UUID purchaseOrderId,
            @Valid @RequestBody CorrectArrivalDateRequest request) {
        return arrivalConfirmationService.correctArrivalDate(purchaseOrderId, request);
    }

    @GetMapping
    ArrivalResponse get(@PathVariable UUID purchaseOrderId) {
        return arrivalConfirmationService.getArrival(purchaseOrderId);
    }
}
