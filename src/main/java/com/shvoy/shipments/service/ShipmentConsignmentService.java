package com.shvoy.shipments.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.NotFoundException;
import com.shvoy.purchaseorders.dto.PurchaseOrderSummary;
import com.shvoy.purchaseorders.service.PurchaseOrderService;
import com.shvoy.shipments.domain.ShipmentConsignment;
import com.shvoy.shipments.dto.ConsignmentSummaryResponse;
import com.shvoy.shipments.repository.ShipmentConsignmentRepository;
import com.shvoy.shipments.repository.ShipmentRepository;
import com.shvoy.suppliers.dto.SupplierSummary;
import com.shvoy.suppliers.service.SupplierService;

/**
 * Story 7.3 — the co-loading workflow: attach additional POs to one BL, detach
 * a mis-linked one, and list a shipment's consignments. Per-consignment
 * independence is the whole point — each portion carries its own documents, its
 * own receipt eligibility, and its own anchor clock; there is deliberately no
 * shipment-level "all documents in" gate (that would recreate exactly the
 * failure the business rule prevents — one slow supplier freezing everyone's
 * payments).
 *
 * <p>Attach commits (via {@link ShipmentConsignmentRecordingService}) and then
 * publishes the newly-attached PO's anchor events — so if the BL is already
 * dated, that supplier's balance clock starts on attachment. Same
 * commit-then-publish, best-effort posture as document logging (7.2).
 */
@Service
public class ShipmentConsignmentService {

    private final ShipmentConsignmentRecordingService recordingService;
    private final ShipmentAnchorPublisher anchorPublisher;
    private final ShipmentRepository shipmentRepository;
    private final ShipmentConsignmentRepository consignmentRepository;
    private final PurchaseOrderService purchaseOrderService;
    private final SupplierService supplierService;

    ShipmentConsignmentService(ShipmentConsignmentRecordingService recordingService,
            ShipmentAnchorPublisher anchorPublisher, ShipmentRepository shipmentRepository,
            ShipmentConsignmentRepository consignmentRepository, PurchaseOrderService purchaseOrderService,
            SupplierService supplierService) {
        this.recordingService = recordingService;
        this.anchorPublisher = anchorPublisher;
        this.shipmentRepository = shipmentRepository;
        this.consignmentRepository = consignmentRepository;
        this.purchaseOrderService = purchaseOrderService;
        this.supplierService = supplierService;
    }

    public List<ConsignmentSummaryResponse> attach(UUID shipmentId, UUID purchaseOrderId) {
        anchorPublisher.publishAll(recordingService.attach(shipmentId, purchaseOrderId));
        return listConsignments(shipmentId);
    }

    public List<ConsignmentSummaryResponse> detach(UUID shipmentId, UUID purchaseOrderId) {
        recordingService.detach(shipmentId, purchaseOrderId);
        return listConsignments(shipmentId);
    }

    /** The "linked POs sharing this BL" view — active (non-detached) consignments, oldest first. */
    @Transactional(readOnly = true)
    public List<ConsignmentSummaryResponse> listConsignments(UUID shipmentId) {
        assertOwnShipmentExists(shipmentId);
        return consignmentRepository.findAll().stream()
            .filter(c -> c.getShipmentId().equals(shipmentId) && !c.isDetached())
            .sorted(Comparator.comparing(ShipmentConsignment::getCreatedAt))
            .map(this::toSummary)
            .toList();
    }

    private ConsignmentSummaryResponse toSummary(ShipmentConsignment consignment) {
        PurchaseOrderSummary po = purchaseOrderService.getSummary(consignment.getPurchaseOrderId());
        SupplierSummary supplier = supplierService.getSummary(po.supplierId());
        return new ConsignmentSummaryResponse(
            consignment.getId(),
            consignment.getPurchaseOrderId(),
            po.poNumber(),
            supplier.id(),
            supplier.name(),
            consignment.getPackingListReference() != null,
            consignment.getInspectionReportReference() != null,
            consignment.getReceiptStatus(),
            consignment.isReceiptEligible());
    }

    private void assertOwnShipmentExists(UUID shipmentId) {
        if (shipmentRepository.findById(shipmentId).isEmpty()) {
            throw new NotFoundException("Shipment not found");
        }
    }
}
