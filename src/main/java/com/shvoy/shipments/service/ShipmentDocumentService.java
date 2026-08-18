package com.shvoy.shipments.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import com.shvoy.NotFoundException;
import com.shvoy.shipments.domain.InspectionOutcome;
import com.shvoy.shipments.domain.Shipment;
import com.shvoy.shipments.domain.ShipmentConsignment;
import com.shvoy.shipments.domain.ShipmentDocumentType;
import com.shvoy.shipments.dto.ConsignmentResponse;
import com.shvoy.shipments.dto.ShipmentResponse;
import com.shvoy.shipments.dto.SkuQuantityRequest;
import com.shvoy.shipments.repository.ShipmentConsignmentRepository;
import com.shvoy.shipments.repository.ShipmentRepository;

/**
 * Story 7.2 — the single "log a shipment document" entry point (mirroring
 * {@code InvoiceService#log} and {@code ProformaInvoiceService#log}): the
 * controller calls it today; a future AI Document Intelligence pipeline would
 * call the same methods with extracted fields. The three log methods each
 * record the document durably (via {@link ShipmentDocumentRecordingService}'s
 * own transaction) and then publish any anchor dates that became known.
 *
 * <p><strong>The anchor-date chain — the reason this story matters.</strong>
 * After a BL/ex-factory date is recorded, this service publishes (via {@link
 * ShipmentAnchorPublisher}) an anchor event per affected PO; {@code
 * PaymentDueDateService} (6.2) reacts and fills in the balance due date. It's
 * the first time a real shipment date drives payment timing. Best-effort: a
 * publish failure never fails the recording. A corrected date re-publishes and
 * 6.2's re-entrancy recalculates and audits.
 */
@Service
public class ShipmentDocumentService {

    private final ShipmentDocumentRecordingService recordingService;
    private final ShipmentRepository shipmentRepository;
    private final ShipmentConsignmentRepository consignmentRepository;
    private final ShipmentAnchorPublisher anchorPublisher;
    private final S3Client s3Client;
    private final String documentsBucket;

    ShipmentDocumentService(ShipmentDocumentRecordingService recordingService, ShipmentRepository shipmentRepository,
            ShipmentConsignmentRepository consignmentRepository, ShipmentAnchorPublisher anchorPublisher,
            S3Client s3Client, @Value("${aws.s3.documents-bucket}") String documentsBucket) {
        this.recordingService = recordingService;
        this.shipmentRepository = shipmentRepository;
        this.consignmentRepository = consignmentRepository;
        this.anchorPublisher = anchorPublisher;
        this.s3Client = s3Client;
        this.documentsBucket = documentsBucket;
    }

    public ShipmentResponse logBillOfLading(UUID purchaseOrderId, String blReference, LocalDate blDate,
            LocalDate exFactoryDate, MultipartFile file) {
        anchorPublisher.publishAll(
            recordingService.recordBillOfLading(purchaseOrderId, blReference, blDate, exFactoryDate, file));
        return getShipmentForPurchaseOrder(purchaseOrderId);
    }

    public ShipmentResponse logPackingList(UUID purchaseOrderId, String reference, LocalDate date,
            List<SkuQuantityRequest> lines, MultipartFile file) {
        anchorPublisher.publishAll(recordingService.recordPackingList(purchaseOrderId, reference, date, lines, file));
        return getShipmentForPurchaseOrder(purchaseOrderId);
    }

    public ShipmentResponse logInspectionReport(UUID purchaseOrderId, String reference, LocalDate date,
            InspectionOutcome outcome, String notes, MultipartFile file) {
        anchorPublisher.publishAll(
            recordingService.recordInspectionReport(purchaseOrderId, reference, date, outcome, notes, file));
        return getShipmentForPurchaseOrder(purchaseOrderId);
    }

    /** Set/clear the inspection-due flag (Story 7.4 revised) — the seam the future scoring engine also drives. */
    public ShipmentResponse setInspectionDue(UUID purchaseOrderId, boolean due, String reason) {
        recordingService.recordInspectionDue(purchaseOrderId, due, reason);
        return getShipmentForPurchaseOrder(purchaseOrderId);
    }

    @Transactional(readOnly = true)
    public ShipmentResponse getShipmentForPurchaseOrder(UUID purchaseOrderId) {
        ShipmentConsignment consignment = findConsignment(purchaseOrderId);
        Shipment shipment = findShipment(consignment.getShipmentId());
        int siblings = (int) consignmentRepository.findAll().stream()
            .filter(c -> c.getShipmentId().equals(shipment.getId()) && !c.isDetached())
            .filter(c -> !c.getId().equals(consignment.getId()))
            .count();
        return toResponse(shipment, consignment, siblings);
    }

    /** Retrieve a stored document's bytes for download. 404 if no shipment exists for the PO, or the document hasn't been logged. */
    @Transactional(readOnly = true)
    public byte[] getDocument(UUID purchaseOrderId, ShipmentDocumentType type) {
        ShipmentConsignment consignment = findConsignment(purchaseOrderId);
        Shipment shipment = findShipment(consignment.getShipmentId());
        String key = switch (type) {
            case BILL_OF_LADING -> shipment.getBlDocumentS3Key();
            case PACKING_LIST -> consignment.getPackingListS3Key();
            case INSPECTION_REPORT -> consignment.getInspectionReportS3Key();
        };
        if (key == null) {
            throw new NotFoundException("Document not logged for this shipment");
        }
        ResponseBytes<GetObjectResponse> object = s3Client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(documentsBucket).key(key).build());
        return object.asByteArray();
    }

    private ShipmentConsignment findConsignment(UUID purchaseOrderId) {
        Optional<ShipmentConsignment> consignment = consignmentRepository.findAll().stream()
            .filter(c -> c.getPurchaseOrderId().equals(purchaseOrderId) && !c.isDetached())
            .findFirst();
        return consignment.orElseThrow(() -> new NotFoundException("No shipment for this purchase order"));
    }

    private Shipment findShipment(UUID shipmentId) {
        return shipmentRepository.findById(shipmentId)
            .orElseThrow(() -> new NotFoundException("Shipment not found"));
    }

    private static ShipmentResponse toResponse(Shipment shipment, ShipmentConsignment consignment, int siblingCount) {
        ConsignmentResponse consignmentResponse = new ConsignmentResponse(
            consignment.getId(),
            consignment.getPurchaseOrderId(),
            consignment.getPackingListReference(),
            consignment.getPackingListDate(),
            consignment.getPackingListS3Key(),
            consignment.getInspectionReportReference(),
            consignment.getInspectionReportDate(),
            consignment.getInspectionReportOutcome(),
            consignment.getInspectionReportS3Key(),
            consignment.getReceiptStatus(),
            consignment.isReceiptEligible(),
            consignment.getConfirmedEtd(),
            consignment.getCreatedAt(),
            consignment.getUpdatedAt());
        return new ShipmentResponse(
            shipment.getId(),
            shipment.getBlReference(),
            shipment.getBlDate(),
            shipment.getExFactoryDate(),
            shipment.getBlDocumentS3Key(),
            consignmentResponse,
            siblingCount > 0,
            siblingCount,
            shipment.getCreatedAt(),
            shipment.getUpdatedAt());
    }
}
