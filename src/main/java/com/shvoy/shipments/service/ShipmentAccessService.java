package com.shvoy.shipments.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.shipments.domain.ReceiptStatus;
import com.shvoy.shipments.domain.Shipment;
import com.shvoy.shipments.domain.ShipmentConsignment;
import com.shvoy.shipments.repository.ShipmentConsignmentRepository;
import com.shvoy.shipments.repository.ShipmentRepository;

/**
 * The shipments module's first cross-module surface (Story 8.1) — the narrow
 * read + first-touch access {@code containerfill} needs to attach a spare-capacity
 * offer to a real container, without reaching into the module's internals.
 * {@code @NamedInterface} so the new dependency is explicit and enforced by
 * {@code ModularityTests}.
 */
@NamedInterface("shipments")
@Service
public class ShipmentAccessService {

    private final ConsignmentProvisioningService consignmentProvisioningService;
    private final ShipmentConsignmentRepository consignmentRepository;
    private final ShipmentRepository shipmentRepository;

    ShipmentAccessService(ConsignmentProvisioningService consignmentProvisioningService,
            ShipmentConsignmentRepository consignmentRepository, ShipmentRepository shipmentRepository) {
        this.consignmentProvisioningService = consignmentProvisioningService;
        this.consignmentRepository = consignmentRepository;
        this.shipmentRepository = shipmentRepository;
    }

    /**
     * The container for this PO — its existing shipment, or a freshly created one
     * on first touch (the same create-on-first-touch path 7.2/7.5 use, keyed by
     * PO). Enforces PO ownership/readiness in the create branch; a cross-tenant PO
     * falls through and 404s there.
     */
    @Transactional
    public UUID resolveOrCreateShipment(UUID purchaseOrderId) {
        return consignmentProvisioningService.resolveOrCreate(purchaseOrderId).getShipmentId();
    }

    /**
     * Whether every active portion of this container has physically arrived — a
     * <em>derived</em> fact (the shipment itself carries no status): true iff there
     * is at least one active consignment and all of them are {@code ARRIVED_CONFIRMED}
     * or {@code ARRIVED_WITH_DISCREPANCY}. Spare capacity on a landed container is
     * meaningless, so 8.1 rejects an offer when this holds.
     */
    @Transactional(readOnly = true)
    public boolean isFullyArrived(UUID shipmentId) {
        List<ShipmentConsignment> active = consignmentRepository.findAll().stream()
            .filter(c -> c.getShipmentId().equals(shipmentId) && !c.isDetached())
            .toList();
        return !active.isEmpty() && active.stream().allMatch(ShipmentAccessService::hasArrived);
    }

    /** The container's BL reference, for display context — null until a BL is logged. */
    @Transactional(readOnly = true)
    public Optional<String> blReferenceOf(UUID shipmentId) {
        return shipmentRepository.findById(shipmentId).map(Shipment::getBlReference);
    }

    private static boolean hasArrived(ShipmentConsignment consignment) {
        ReceiptStatus status = consignment.getReceiptStatus();
        return status == ReceiptStatus.ARRIVED_CONFIRMED || status == ReceiptStatus.ARRIVED_WITH_DISCREPANCY;
    }
}
