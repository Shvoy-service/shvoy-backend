package com.shvoy.shipments.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.CurrentUserContext;
import com.shvoy.ErrorCode;
import com.shvoy.NotFoundException;
import com.shvoy.purchaseorders.service.PurchaseOrderService;
import com.shvoy.shipments.domain.ReceiptStatus;
import com.shvoy.shipments.domain.Shipment;
import com.shvoy.shipments.domain.ShipmentConsignment;
import com.shvoy.shipments.domain.ShipmentDocumentAuditEvent;
import com.shvoy.shipments.domain.ShipmentDocumentAuditEventType;
import com.shvoy.shipments.repository.ShipmentConsignmentRepository;
import com.shvoy.shipments.repository.ShipmentDocumentAuditEventRepository;
import com.shvoy.shipments.repository.ShipmentRepository;
import com.shvoy.suppliers.domain.AnchorEvent;

/**
 * The transactional writes for co-loading (Story 7.3) — attach a PO to an
 * existing shipment, or detach a mis-linked one — on its own bean so {@link
 * ShipmentConsignmentService} can commit the attach durably <em>before</em>
 * publishing the newly-attached PO's anchor events, the same commit-then-publish
 * ordering document logging uses (7.2).
 */
@Service
class ShipmentConsignmentRecordingService {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentConsignmentRepository consignmentRepository;
    private final ShipmentDocumentAuditEventRepository auditRepository;
    private final PurchaseOrderService purchaseOrderService;

    ShipmentConsignmentRecordingService(ShipmentRepository shipmentRepository,
            ShipmentConsignmentRepository consignmentRepository,
            ShipmentDocumentAuditEventRepository auditRepository, PurchaseOrderService purchaseOrderService) {
        this.shipmentRepository = shipmentRepository;
        this.consignmentRepository = consignmentRepository;
        this.auditRepository = auditRepository;
        this.purchaseOrderService = purchaseOrderService;
    }

    /**
     * Co-load a finalised PO onto an existing shipment. The PO must be
     * {@code GENERATED}/{@code SENT} and belong to the same tenant (both enforced
     * by {@code assertOwnPurchaseOrderReadyForShipment} — cross-tenant resolves to
     * 404 even when the shipment id is known), and not already consigned here.
     *
     * <p>Returns the anchor events to publish: if the BL/ex-factory date is
     * <em>already</em> set, the newly-attached PO's clock starts the moment it
     * joins the dated BL — this is 7.2's publishing loop earning its keep.
     */
    @Transactional
    List<AnchorPublication> attach(UUID shipmentId, UUID purchaseOrderId) {
        Shipment shipment = findOwnShipment(shipmentId);
        purchaseOrderService.assertOwnPurchaseOrderReadyForShipment(purchaseOrderId);

        boolean alreadyConsigned = consignmentRepository.findAll().stream()
            .anyMatch(c -> c.getShipmentId().equals(shipmentId)
                && c.getPurchaseOrderId().equals(purchaseOrderId) && !c.isDetached());
        if (alreadyConsigned) {
            throw new ConflictException(ErrorCode.PO_ALREADY_CONSIGNED,
                "Purchase order is already consigned on this shipment");
        }

        ShipmentConsignment consignment =
            consignmentRepository.save(new ShipmentConsignment(shipmentId, purchaseOrderId));
        audit(shipmentId, consignment.getId(), purchaseOrderId, ShipmentDocumentAuditEventType.CONSIGNMENT_ATTACHED,
            "PO co-loaded onto shipment " + shipmentId
                + (shipment.getBlReference() == null ? "" : " (BL " + shipment.getBlReference() + ")"));

        List<AnchorPublication> publications = new ArrayList<>();
        if (shipment.getBlDate() != null) {
            publications.add(new AnchorPublication(purchaseOrderId, AnchorEvent.BL, shipment.getBlDate()));
        }
        if (shipment.getExFactoryDate() != null) {
            publications.add(
                new AnchorPublication(purchaseOrderId, AnchorEvent.EX_FACTORY, shipment.getExFactoryDate()));
        }
        return publications;
    }

    /**
     * Detach a mis-linked consignment. Allowed only while {@code
     * DOCUMENTS_PENDING} — once provisionally receipted (7.4) it's an unwind, not
     * a correction ({@code CONSIGNMENT_NOT_DETACHABLE}). Soft-delete: the row and
     * its audit trail are retained.
     */
    @Transactional
    void detach(UUID shipmentId, UUID purchaseOrderId) {
        ShipmentConsignment consignment = findOwnActiveConsignment(shipmentId, purchaseOrderId);
        if (consignment.getReceiptStatus() != ReceiptStatus.DOCUMENTS_PENDING) {
            throw new ConflictException(ErrorCode.CONSIGNMENT_NOT_DETACHABLE,
                "Consignment cannot be detached in status " + consignment.getReceiptStatus());
        }
        consignment.detach();
        consignmentRepository.save(consignment);
        audit(shipmentId, consignment.getId(), purchaseOrderId, ShipmentDocumentAuditEventType.CONSIGNMENT_DETACHED,
            "PO detached from shipment " + shipmentId + " while still DOCUMENTS_PENDING");
    }

    private Shipment findOwnShipment(UUID shipmentId) {
        return shipmentRepository.findById(shipmentId)
            .orElseThrow(() -> new NotFoundException("Shipment not found"));
    }

    private ShipmentConsignment findOwnActiveConsignment(UUID shipmentId, UUID purchaseOrderId) {
        Optional<ShipmentConsignment> consignment = consignmentRepository.findAll().stream()
            .filter(c -> c.getShipmentId().equals(shipmentId)
                && c.getPurchaseOrderId().equals(purchaseOrderId) && !c.isDetached())
            .findFirst();
        return consignment.orElseThrow(() -> new NotFoundException("Consignment not found on this shipment"));
    }

    private void audit(UUID shipmentId, UUID consignmentId, UUID purchaseOrderId,
            ShipmentDocumentAuditEventType type, String detail) {
        auditRepository.save(new ShipmentDocumentAuditEvent(
            shipmentId, consignmentId, purchaseOrderId, type, detail, CurrentUserContext.get()));
    }
}
