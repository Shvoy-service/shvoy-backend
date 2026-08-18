package com.shvoy.shipments.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.shvoy.shipments.domain.Shipment;
import com.shvoy.shipments.domain.ShipmentConsignment;
import com.shvoy.shipments.repository.ShipmentConsignmentRepository;
import com.shvoy.shipments.repository.ShipmentRepository;
import com.shvoy.purchaseorders.service.PurchaseOrderService;

/**
 * The one <strong>create-on-first-touch</strong> path (Story 7.2, extracted here
 * in 7.5 so document logging and ETD logging genuinely share it). Shipment
 * records are created on first shipment contact — whichever piece of shipment
 * data arrives first (a BL, a packing list, or — new in 7.5 — a confirmed ETD
 * that a supplier gives weeks ahead of any document). Reusing one path keeps
 * "first touch" meaning the same thing regardless of which touch it is.
 *
 * <p>The reuse path only matches consignments in the caller's own tenant
 * ({@code findAll} is tenant-scoped), so a cross-tenant PO id falls through to
 * the create path and is rejected there (the finalisation/ownership check → 404).
 */
@Service
class ConsignmentProvisioningService {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentConsignmentRepository consignmentRepository;
    private final PurchaseOrderService purchaseOrderService;

    ConsignmentProvisioningService(ShipmentRepository shipmentRepository,
            ShipmentConsignmentRepository consignmentRepository, PurchaseOrderService purchaseOrderService) {
        this.shipmentRepository = shipmentRepository;
        this.consignmentRepository = consignmentRepository;
        this.purchaseOrderService = purchaseOrderService;
    }

    /** The PO's existing active consignment, or a freshly created shipment + consignment on first touch. */
    ShipmentConsignment resolveOrCreate(UUID purchaseOrderId) {
        Optional<ShipmentConsignment> existing = consignmentRepository.findAll().stream()
            .filter(c -> c.getPurchaseOrderId().equals(purchaseOrderId) && !c.isDetached())
            .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        purchaseOrderService.assertOwnPurchaseOrderReadyForShipment(purchaseOrderId);
        Shipment shipment = shipmentRepository.save(new Shipment(null, null, null, null));
        return consignmentRepository.save(new ShipmentConsignment(shipment.getId(), purchaseOrderId));
    }
}
