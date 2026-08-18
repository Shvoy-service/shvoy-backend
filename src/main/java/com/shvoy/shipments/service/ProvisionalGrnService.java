package com.shvoy.shipments.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.NotFoundException;
import com.shvoy.payments.event.ProvisionalGoodsReceiptEvent;
import com.shvoy.payments.event.QcFailureEvent;
import com.shvoy.shipments.domain.GrnProvenance;
import com.shvoy.shipments.domain.ReceiptStatus;
import com.shvoy.shipments.domain.ShipmentConsignment;
import com.shvoy.shipments.dto.AmendGoodsReceiptRequest;
import com.shvoy.shipments.dto.GoodsReceiptLineResponse;
import com.shvoy.shipments.dto.GoodsReceiptResponse;
import com.shvoy.shipments.repository.GoodsReceiptLineRepository;
import com.shvoy.shipments.repository.ShipmentConsignmentRepository;

/**
 * Story 7.4 — the provisional goods receipt: the record the three-way match
 * (6.5) runs against, created once a consignment's documents are in, explicitly
 * <strong>without</strong> requiring physical arrival. Landing this unblocks
 * 6.5, 6.6, and 6.8.
 *
 * <p>Creation is an <strong>explicit user action</strong>, not automatic on
 * document upload — clicking it certifies "the documents are in and correct
 * enough to receipt", the human-in-the-loop the roadmap keeps insisting on
 * (auto-creation would let a typo'd packing list silently open the payment
 * gate). It commits (via {@link ProvisionalGrnRecordingService}) and then
 * publishes the {@link ProvisionalGoodsReceiptEvent} for 6.5, best-effort — a
 * publish failure never fails the receipt.
 */
@Service
public class ProvisionalGrnService {

    private static final Logger log = LoggerFactory.getLogger(ProvisionalGrnService.class);

    private final ProvisionalGrnRecordingService recordingService;
    private final ApplicationEventPublisher eventPublisher;
    private final ShipmentConsignmentRepository consignmentRepository;
    private final GoodsReceiptLineRepository goodsReceiptLineRepository;
    private final ReceiptRollupService receiptRollupService;

    ProvisionalGrnService(ProvisionalGrnRecordingService recordingService, ApplicationEventPublisher eventPublisher,
            ShipmentConsignmentRepository consignmentRepository, GoodsReceiptLineRepository goodsReceiptLineRepository,
            ReceiptRollupService receiptRollupService) {
        this.recordingService = recordingService;
        this.eventPublisher = eventPublisher;
        this.consignmentRepository = consignmentRepository;
        this.goodsReceiptLineRepository = goodsReceiptLineRepository;
        this.receiptRollupService = receiptRollupService;
    }

    public GoodsReceiptResponse create(UUID purchaseOrderId) {
        ProvisionalGrnRecordingService.GrnCreationResult result = recordingService.create(purchaseOrderId);
        publish(result.receiptEvent());
        if (result.qcFailed()) {
            // Opens the quality/dispute lane (7.4 revised); best-effort, never fails the committed receipt.
            try {
                eventPublisher.publishEvent(new QcFailureEvent(result.purchaseOrderId(), result.consignmentId()));
            } catch (RuntimeException e) {
                log.warn("QC-failure publish failed for PO {} — GRN remains recorded (flagged qc_failed)",
                    result.purchaseOrderId(), e);
            }
        }
        reassessClosure(purchaseOrderId);
        return getForPurchaseOrder(purchaseOrderId);
    }

    public GoodsReceiptResponse amend(UUID purchaseOrderId, AmendGoodsReceiptRequest request) {
        publish(recordingService.amend(purchaseOrderId, request));
        reassessClosure(purchaseOrderId);
        return getForPurchaseOrder(purchaseOrderId);
    }

    /**
     * Re-evaluate PO closure from the now-committed cumulative receipt (receipt
     * rollup &amp; PO closure). Best-effort like the seam publish — the GRN is
     * already durable; a closure hiccup is re-evaluated on the next receipt event
     * rather than failing the receipt.
     */
    private void reassessClosure(UUID purchaseOrderId) {
        try {
            receiptRollupService.reassessClosure(purchaseOrderId);
        } catch (RuntimeException e) {
            log.warn("Closure re-assessment failed for PO {} — the GRN remains recorded", purchaseOrderId, e);
        }
    }

    /** Publish the GRN to the 6.2/6.1-style seam best-effort — a downstream failure never fails the committed receipt. */
    private void publish(ProvisionalGoodsReceiptEvent event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (RuntimeException e) {
            log.warn("Provisional-GRN publish failed for PO {} — the GRN remains recorded", event.purchaseOrderId(), e);
        }
    }

    /** The Feature 7 → 6 read: does a GRN exist for this PO, and what quantities does it state. */
    @Transactional(readOnly = true)
    public GoodsReceiptResponse getForPurchaseOrder(UUID purchaseOrderId) {
        ShipmentConsignment consignment = findActiveConsignment(purchaseOrderId);
        boolean exists = consignment.getReceiptStatus() != ReceiptStatus.DOCUMENTS_PENDING;
        List<GoodsReceiptLineResponse> lines = goodsReceiptLineRepository.findAll().stream()
            .filter(l -> l.getConsignmentId().equals(consignment.getId()))
            .map(l -> new GoodsReceiptLineResponse(l.getSkuId(), l.getReceivedQuantity()))
            .toList();
        return new GoodsReceiptResponse(
            purchaseOrderId,
            consignment.getId(),
            exists,
            consignment.getReceiptStatus(),
            consignment.getGrnProvenance(),
            consignment.getGrnProvenance() == GrnProvenance.QC_FAILED,
            consignment.getProvisionallyReceiptedBy(),
            consignment.getProvisionallyReceiptedAt(),
            lines);
    }

    private ShipmentConsignment findActiveConsignment(UUID purchaseOrderId) {
        Optional<ShipmentConsignment> consignment = consignmentRepository.findAll().stream()
            .filter(c -> c.getPurchaseOrderId().equals(purchaseOrderId) && !c.isDetached())
            .findFirst();
        return consignment.orElseThrow(() -> new NotFoundException("No shipment for this purchase order"));
    }
}
