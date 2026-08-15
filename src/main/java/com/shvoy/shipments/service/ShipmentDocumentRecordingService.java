package com.shvoy.shipments.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import com.shvoy.CurrentUserContext;
import com.shvoy.NotFoundException;
import com.shvoy.TenantContext;
import com.shvoy.ValidationException;
import com.shvoy.purchaseorders.service.PurchaseOrderService;
import com.shvoy.shipments.domain.Shipment;
import com.shvoy.shipments.domain.ShipmentConsignment;
import com.shvoy.shipments.domain.ShipmentDocumentAuditEvent;
import com.shvoy.shipments.domain.ShipmentDocumentAuditEventType;
import com.shvoy.shipments.domain.ShipmentDocumentType;
import com.shvoy.shipments.repository.ShipmentConsignmentRepository;
import com.shvoy.shipments.repository.ShipmentDocumentAuditEventRepository;
import com.shvoy.shipments.repository.ShipmentRepository;
import com.shvoy.suppliers.domain.AnchorEvent;

/**
 * The actual "record a shipment document" write — Story 7.2, mirroring {@code
 * InvoiceRecordingService} (6.4). Its own {@code @Transactional} on a separate
 * bean so {@link ShipmentDocumentService} commits the document durably
 * <em>before</em> firing the anchor-date events, exactly as invoice/PI logging
 * commit before their triggers.
 *
 * <p><strong>Entry point of the whole workflow.</strong> Logging the first
 * document for a PO creates the {@link Shipment} + its {@link
 * ShipmentConsignment} (no speculative empty records at PO-send time); a later
 * document reuses them. The PO must be {@code GENERATED}/{@code SENT} — you
 * can't ship a draft ({@code PO_NOT_READY_FOR_SHIPMENT}).
 *
 * <p><strong>Record faithfully.</strong> Validation is well-formedness only —
 * non-blank references, a present BL date, a non-empty file. Cross-document
 * consistency (does the BL quantity match the packing list?) is deliberately
 * <em>not</em> checked; that's the AI layer's job (Feature 10). Corrections
 * re-run through the same methods: structured-field changes are audited
 * (old→new), and a replacement file is stored under a fresh key while the prior
 * S3 object is retained, never deleted.
 */
@Service
class ShipmentDocumentRecordingService {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentConsignmentRepository consignmentRepository;
    private final ShipmentDocumentAuditEventRepository auditRepository;
    private final PurchaseOrderService purchaseOrderService;
    private final S3Client s3Client;
    private final String documentsBucket;

    ShipmentDocumentRecordingService(ShipmentRepository shipmentRepository,
            ShipmentConsignmentRepository consignmentRepository,
            ShipmentDocumentAuditEventRepository auditRepository,
            PurchaseOrderService purchaseOrderService, S3Client s3Client,
            @Value("${aws.s3.documents-bucket}") String documentsBucket) {
        this.shipmentRepository = shipmentRepository;
        this.consignmentRepository = consignmentRepository;
        this.auditRepository = auditRepository;
        this.purchaseOrderService = purchaseOrderService;
        this.s3Client = s3Client;
        this.documentsBucket = documentsBucket;
    }

    @Transactional
    List<AnchorPublication> recordBillOfLading(UUID purchaseOrderId, String blReference, LocalDate blDate,
            LocalDate exFactoryDate, MultipartFile file) {
        assertNotBlank("blReference", blReference);
        if (blDate == null) {
            throw new ValidationException("blDate is required");
        }
        UUID actor = CurrentUserContext.get();
        ShipmentConsignment consignment = resolveOrCreateConsignment(purchaseOrderId);
        Shipment shipment = getShipment(consignment.getShipmentId());

        boolean first = shipment.getBlReference() == null;
        LocalDate oldBlDate = shipment.getBlDate();
        LocalDate oldExFactory = shipment.getExFactoryDate();
        String oldKey = shipment.getBlDocumentS3Key();

        String newKey = storeInS3(shipment.getId(), ShipmentDocumentType.BILL_OF_LADING, file);
        shipment.recordBillOfLading(blReference, blDate, newKey);
        if (exFactoryDate != null) {
            shipment.recordExFactoryDate(exFactoryDate);
        }
        shipmentRepository.save(shipment);

        if (first) {
            audit(shipment.getId(), null, purchaseOrderId, ShipmentDocumentAuditEventType.BILL_OF_LADING_LOGGED,
                "BL " + blReference + " dated " + blDate
                    + (exFactoryDate == null ? "" : ", ex-factory " + exFactoryDate), actor);
        } else {
            if (!Objects.equals(oldBlDate, blDate)) {
                auditCorrection(shipment.getId(), null, purchaseOrderId, "BL date", oldBlDate, blDate, actor);
            }
            if (exFactoryDate != null && !Objects.equals(oldExFactory, exFactoryDate)) {
                auditCorrection(shipment.getId(), null, purchaseOrderId, "ex-factory date", oldExFactory, exFactoryDate,
                    actor);
            }
            if (oldKey != null) {
                auditFileSuperseded(shipment.getId(), null, purchaseOrderId, "BL document", oldKey, actor);
            }
        }

        // A shipment-level BL/ex-factory date fans out over every consignment (co-loading; one here).
        List<AnchorPublication> publications = new ArrayList<>();
        for (ShipmentConsignment c : consignmentsOf(shipment.getId())) {
            publications.add(new AnchorPublication(c.getPurchaseOrderId(), AnchorEvent.BL, blDate));
            if (exFactoryDate != null) {
                publications.add(new AnchorPublication(c.getPurchaseOrderId(), AnchorEvent.EX_FACTORY, exFactoryDate));
            }
        }
        return publications;
    }

    @Transactional
    List<AnchorPublication> recordPackingList(UUID purchaseOrderId, String reference, LocalDate date,
            MultipartFile file) {
        assertNotBlank("packingListReference", reference);
        UUID actor = CurrentUserContext.get();
        ShipmentConsignment consignment = resolveOrCreateConsignment(purchaseOrderId);

        boolean first = consignment.getPackingListReference() == null;
        LocalDate oldDate = consignment.getPackingListDate();
        String oldKey = consignment.getPackingListS3Key();

        String newKey = storeInS3(consignment.getShipmentId(), ShipmentDocumentType.PACKING_LIST, file);
        consignment.recordPackingList(reference, date, newKey);
        consignmentRepository.save(consignment);

        auditDocumentWrite(consignment, purchaseOrderId, first,
            ShipmentDocumentAuditEventType.PACKING_LIST_LOGGED,
            "Packing list " + reference + (date == null ? "" : " dated " + date),
            "packing-list date", oldDate, date, "packing list", oldKey, actor);

        // No anchor: packing-list/inspection dates don't drive payment timing.
        return List.of();
    }

    @Transactional
    List<AnchorPublication> recordInspectionReport(UUID purchaseOrderId, String reference, LocalDate date,
            String outcome, MultipartFile file) {
        assertNotBlank("inspectionReportReference", reference);
        UUID actor = CurrentUserContext.get();
        ShipmentConsignment consignment = resolveOrCreateConsignment(purchaseOrderId);

        boolean first = consignment.getInspectionReportReference() == null;
        LocalDate oldDate = consignment.getInspectionReportDate();
        String oldKey = consignment.getInspectionReportS3Key();

        String newKey = storeInS3(consignment.getShipmentId(), ShipmentDocumentType.INSPECTION_REPORT, file);
        consignment.recordInspectionReport(reference, date, outcome, newKey);
        consignmentRepository.save(consignment);

        auditDocumentWrite(consignment, purchaseOrderId, first,
            ShipmentDocumentAuditEventType.INSPECTION_REPORT_LOGGED,
            "Inspection report " + reference + (date == null ? "" : " dated " + date)
                + (outcome == null ? "" : ", outcome " + outcome),
            "inspection-report date", oldDate, date, "inspection report", oldKey, actor);

        return List.of();
    }

    /** Shared audit for a per-consignment document: first log, else audit a date correction and/or a file supersession. */
    private void auditDocumentWrite(ShipmentConsignment consignment, UUID purchaseOrderId, boolean first,
            ShipmentDocumentAuditEventType loggedType, String loggedDetail, String dateFieldLabel,
            LocalDate oldDate, LocalDate newDate, String fileLabel, String oldKey, UUID actor) {
        if (first) {
            audit(consignment.getShipmentId(), consignment.getId(), purchaseOrderId, loggedType, loggedDetail, actor);
            return;
        }
        if (!Objects.equals(oldDate, newDate)) {
            auditCorrection(consignment.getShipmentId(), consignment.getId(), purchaseOrderId, dateFieldLabel,
                oldDate, newDate, actor);
        }
        if (oldKey != null) {
            auditFileSuperseded(consignment.getShipmentId(), consignment.getId(), purchaseOrderId, fileLabel, oldKey,
                actor);
        }
    }

    /**
     * Find the PO's existing consignment, or create the shipment + consignment on
     * first document. Ownership/readiness is asserted on the create path; the
     * reuse path only matches consignments in the caller's own tenant (findAll is
     * tenant-scoped), so a cross-tenant PO id falls through to the create path and
     * is rejected there with a 404.
     */
    private ShipmentConsignment resolveOrCreateConsignment(UUID purchaseOrderId) {
        Optional<ShipmentConsignment> existing = consignmentRepository.findAll().stream()
            .filter(c -> c.getPurchaseOrderId().equals(purchaseOrderId))
            .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        purchaseOrderService.assertOwnPurchaseOrderReadyForShipment(purchaseOrderId);
        Shipment shipment = shipmentRepository.save(new Shipment(null, null, null, null));
        return consignmentRepository.save(new ShipmentConsignment(shipment.getId(), purchaseOrderId));
    }

    private List<ShipmentConsignment> consignmentsOf(UUID shipmentId) {
        return consignmentRepository.findAll().stream()
            .filter(c -> c.getShipmentId().equals(shipmentId))
            .toList();
    }

    private Shipment getShipment(UUID shipmentId) {
        return shipmentRepository.findById(shipmentId)
            .orElseThrow(() -> new NotFoundException("Shipment not found"));
    }

    private String storeInS3(UUID shipmentId, ShipmentDocumentType type, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("file is required");
        }
        byte[] content = readBytes(file);
        String key = "shipment-documents/%s/%s/%s/%s-%s".formatted(
            TenantContext.get(), shipmentId, type.name().toLowerCase(),
            UUID.randomUUID(), file.getOriginalFilename() == null ? "document" : file.getOriginalFilename());
        s3Client.putObject(
            PutObjectRequest.builder().bucket(documentsBucket).key(key).build(),
            RequestBody.fromBytes(content));
        return key;
    }

    private void audit(UUID shipmentId, UUID consignmentId, UUID purchaseOrderId,
            ShipmentDocumentAuditEventType type, String detail, UUID actor) {
        auditRepository.save(
            new ShipmentDocumentAuditEvent(shipmentId, consignmentId, purchaseOrderId, type, detail, actor));
    }

    private void auditCorrection(UUID shipmentId, UUID consignmentId, UUID purchaseOrderId, String field,
            Object oldValue, Object newValue, UUID actor) {
        audit(shipmentId, consignmentId, purchaseOrderId, ShipmentDocumentAuditEventType.DOCUMENT_FIELD_CORRECTED,
            field + " " + oldValue + " → " + newValue, actor);
    }

    private void auditFileSuperseded(UUID shipmentId, UUID consignmentId, UUID purchaseOrderId, String label,
            String retainedKey, UUID actor) {
        audit(shipmentId, consignmentId, purchaseOrderId, ShipmentDocumentAuditEventType.DOCUMENT_FILE_SUPERSEDED,
            label + " replaced (prior file retained: " + retainedKey + ")", actor);
    }

    private static void assertNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field + " must not be blank");
        }
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read uploaded file", e);
        }
    }
}
