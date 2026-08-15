package com.shvoy.shipments.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.CurrentUserContext;
import com.shvoy.ErrorCode;
import com.shvoy.NotFoundException;
import com.shvoy.ValidationException;
import com.shvoy.payments.event.ProvisionalGoodsReceiptEvent;
import com.shvoy.payments.event.ProvisionalGoodsReceiptLine;
import com.shvoy.shipments.domain.GoodsReceiptLine;
import com.shvoy.shipments.domain.GrnProvenance;
import com.shvoy.shipments.domain.PackingListLine;
import com.shvoy.shipments.domain.ReceiptStatus;
import com.shvoy.shipments.domain.Shipment;
import com.shvoy.shipments.domain.ShipmentConsignment;
import com.shvoy.shipments.domain.ShipmentDocumentAuditEvent;
import com.shvoy.shipments.domain.ShipmentDocumentAuditEventType;
import com.shvoy.shipments.dto.AmendGoodsReceiptRequest;
import com.shvoy.shipments.dto.SkuQuantityRequest;
import com.shvoy.shipments.repository.GoodsReceiptLineRepository;
import com.shvoy.shipments.repository.PackingListLineRepository;
import com.shvoy.shipments.repository.ShipmentConsignmentRepository;
import com.shvoy.shipments.repository.ShipmentDocumentAuditEventRepository;
import com.shvoy.shipments.repository.ShipmentRepository;

/**
 * The transactional writes for the provisional GRN (Story 7.4) — create and
 * amend — on their own bean so {@link ProvisionalGrnService} commits durably
 * <em>before</em> publishing the {@link ProvisionalGoodsReceiptEvent}, the same
 * commit-then-publish ordering the rest of Feature 7 uses.
 *
 * <p>The GRN is per-consignment (each PO's portion receipts independently, 7.3)
 * and explicitly does <strong>not</strong> require physical arrival. Its
 * substance is the per-SKU received quantities, <strong>snapshotted</strong>
 * from the packing list at receipt time — never a live reference, so a later
 * packing-list correction doesn't silently rewrite an issued GRN.
 */
@Service
class ProvisionalGrnRecordingService {

    private final ShipmentConsignmentRepository consignmentRepository;
    private final ShipmentRepository shipmentRepository;
    private final PackingListLineRepository packingListLineRepository;
    private final GoodsReceiptLineRepository goodsReceiptLineRepository;
    private final ShipmentDocumentAuditEventRepository auditRepository;
    private final ProvisionalGrnGate gate;

    ProvisionalGrnRecordingService(ShipmentConsignmentRepository consignmentRepository,
            ShipmentRepository shipmentRepository, PackingListLineRepository packingListLineRepository,
            GoodsReceiptLineRepository goodsReceiptLineRepository,
            ShipmentDocumentAuditEventRepository auditRepository, ProvisionalGrnGate gate) {
        this.consignmentRepository = consignmentRepository;
        this.shipmentRepository = shipmentRepository;
        this.packingListLineRepository = packingListLineRepository;
        this.goodsReceiptLineRepository = goodsReceiptLineRepository;
        this.auditRepository = auditRepository;
        this.gate = gate;
    }

    @Transactional
    GrnCreationResult create(UUID purchaseOrderId) {
        ShipmentConsignment consignment = findActiveConsignment(purchaseOrderId);
        Shipment shipment = findShipment(consignment.getShipmentId());
        gate.assertEligible(shipment, consignment);
        if (consignment.getReceiptStatus() != ReceiptStatus.DOCUMENTS_PENDING) {
            throw new ConflictException(ErrorCode.PROVISIONAL_GRN_EXISTS,
                "A provisional GRN already exists for this consignment (" + consignment.getReceiptStatus() + ")");
        }

        List<PackingListLine> packingLines = packingListLinesOf(consignment.getId());
        if (packingLines.isEmpty()) {
            throw new ValidationException("packing list has no line quantities to receipt");
        }
        for (PackingListLine line : packingLines) {
            goodsReceiptLineRepository.save(
                new GoodsReceiptLine(consignment.getId(), line.getSkuId(), line.getQuantity()));
        }

        GrnProvenance provenance = gate.provenanceFor(consignment);
        consignment.receiptProvisionally(CurrentUserContext.get(), provenance);
        consignmentRepository.save(consignment);
        audit(consignment, purchaseOrderId, ShipmentDocumentAuditEventType.PROVISIONAL_GRN_CREATED,
            "Provisional GRN created from packing list (" + packingLines.size() + " line(s)), provenance " + provenance
                + "; physical arrival not required");

        boolean qcFailed = provenance == GrnProvenance.QC_FAILED;
        if (qcFailed) {
            // The GRN creates anyway; a QC-failure event opens the quality/dispute lane. The match is NOT blocked.
            audit(consignment, purchaseOrderId, ShipmentDocumentAuditEventType.GRN_QC_FAILED,
                "GRN created despite a failed inspection — flagged qc_failed; quality dispute runs alongside payment control");
        }
        return new GrnCreationResult(buildEvent(purchaseOrderId, consignment.getId()), qcFailed,
            purchaseOrderId, consignment.getId());
    }

    /** The events a GRN creation produces: always the receipted event (6.5's trigger), plus a QC-failure flag (7.4 revised). */
    record GrnCreationResult(ProvisionalGoodsReceiptEvent receiptEvent, boolean qcFailed, UUID purchaseOrderId,
            UUID consignmentId) {
    }

    @Transactional
    ProvisionalGoodsReceiptEvent amend(UUID purchaseOrderId, AmendGoodsReceiptRequest request) {
        ShipmentConsignment consignment = findActiveConsignment(purchaseOrderId);
        if (consignment.getReceiptStatus() == ReceiptStatus.DOCUMENTS_PENDING) {
            throw new NotFoundException("No provisional GRN to amend for this purchase order");
        }
        if (consignment.getReceiptStatus() != ReceiptStatus.PROVISIONALLY_RECEIPTED) {
            throw new ConflictException(ErrorCode.PROVISIONAL_GRN_NOT_AMENDABLE,
                "GRN is settled (" + consignment.getReceiptStatus() + ") — arrival differences are a discrepancy, not an amendment");
        }

        String before = describeLines(goodsReceiptLinesOf(consignment.getId()).stream()
            .map(l -> new SkuQuantityRequest(l.getSkuId(), l.getReceivedQuantity())).toList());
        replaceGoodsReceiptLines(consignment.getId(), request.lines());
        String after = describeLines(request.lines());
        audit(consignment, purchaseOrderId, ShipmentDocumentAuditEventType.PROVISIONAL_GRN_AMENDED,
            "GRN quantities amended [" + before + " → " + after + "] — reason: " + request.reason());

        return buildEvent(purchaseOrderId, consignment.getId());
    }

    private void replaceGoodsReceiptLines(UUID consignmentId, List<SkuQuantityRequest> lines) {
        goodsReceiptLinesOf(consignmentId).forEach(goodsReceiptLineRepository::delete);
        for (SkuQuantityRequest line : lines) {
            goodsReceiptLineRepository.save(new GoodsReceiptLine(consignmentId, line.skuId(), line.quantity()));
        }
    }

    private ProvisionalGoodsReceiptEvent buildEvent(UUID purchaseOrderId, UUID consignmentId) {
        List<ProvisionalGoodsReceiptLine> lines = goodsReceiptLinesOf(consignmentId).stream()
            .map(l -> new ProvisionalGoodsReceiptLine(l.getSkuId(), l.getReceivedQuantity()))
            .toList();
        return new ProvisionalGoodsReceiptEvent(purchaseOrderId, consignmentId, lines);
    }

    private ShipmentConsignment findActiveConsignment(UUID purchaseOrderId) {
        Optional<ShipmentConsignment> consignment = consignmentRepository.findAll().stream()
            .filter(c -> c.getPurchaseOrderId().equals(purchaseOrderId) && !c.isDetached())
            .findFirst();
        return consignment.orElseThrow(() -> new NotFoundException("No shipment for this purchase order"));
    }

    private Shipment findShipment(UUID shipmentId) {
        return shipmentRepository.findById(shipmentId)
            .orElseThrow(() -> new NotFoundException("Shipment not found"));
    }

    private List<PackingListLine> packingListLinesOf(UUID consignmentId) {
        return packingListLineRepository.findAll().stream()
            .filter(l -> l.getConsignmentId().equals(consignmentId))
            .toList();
    }

    private List<GoodsReceiptLine> goodsReceiptLinesOf(UUID consignmentId) {
        return goodsReceiptLineRepository.findAll().stream()
            .filter(l -> l.getConsignmentId().equals(consignmentId))
            .toList();
    }

    private static String describeLines(List<SkuQuantityRequest> lines) {
        return lines.stream()
            .map(l -> l.skuId() + ":" + l.quantity())
            .collect(Collectors.joining(", "));
    }

    private void audit(ShipmentConsignment consignment, UUID purchaseOrderId, ShipmentDocumentAuditEventType type,
            String detail) {
        auditRepository.save(new ShipmentDocumentAuditEvent(
            consignment.getShipmentId(), consignment.getId(), purchaseOrderId, type, detail, CurrentUserContext.get()));
    }
}
