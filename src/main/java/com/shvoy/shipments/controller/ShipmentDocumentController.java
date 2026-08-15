package com.shvoy.shipments.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.shvoy.ValidationException;
import com.shvoy.shipments.domain.ShipmentDocumentType;
import com.shvoy.shipments.dto.ShipmentResponse;
import com.shvoy.shipments.dto.SkuQuantityRequest;
import com.shvoy.shipments.service.ShipmentDocumentService;

/**
 * Story 7.2 — Screen 5's three upload panels. Each document is a multipart POST
 * (structured fields + the source file); logging the first one creates the
 * shipment for the PO. Re-posting the same document type corrects it (fields
 * audited, prior file retained). Logging is {@code PURCHASING}/{@code ADMIN}
 * (Purchasing owns supplier/shipment documents); reads are open to any company
 * user.
 *
 * <p>Dates are accepted as ISO strings and parsed here into {@code LocalDate}
 * so a malformed date is a clean {@code VALIDATION_ERROR}, not a framework
 * binding failure.
 */
@RestController
@RequestMapping("/api/purchase-orders/{purchaseOrderId}/shipment")
class ShipmentDocumentController {

    private final ShipmentDocumentService shipmentDocumentService;
    private final ObjectMapper objectMapper;

    ShipmentDocumentController(ShipmentDocumentService shipmentDocumentService, ObjectMapper objectMapper) {
        this.shipmentDocumentService = shipmentDocumentService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/bill-of-lading", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    ResponseEntity<ShipmentResponse> logBillOfLading(
            @PathVariable UUID purchaseOrderId,
            @RequestParam("blReference") String blReference,
            @RequestParam("blDate") String blDate,
            @RequestParam(value = "exFactoryDate", required = false) String exFactoryDate,
            @RequestParam("file") MultipartFile file) {
        ShipmentResponse response = shipmentDocumentService.logBillOfLading(
            purchaseOrderId, blReference, parseDate("blDate", blDate), parseOptionalDate("exFactoryDate", exFactoryDate),
            file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping(path = "/packing-list", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    ResponseEntity<ShipmentResponse> logPackingList(
            @PathVariable UUID purchaseOrderId,
            @RequestParam("reference") String reference,
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "lines", required = false) String lines,
            @RequestParam("file") MultipartFile file) {
        ShipmentResponse response = shipmentDocumentService.logPackingList(
            purchaseOrderId, reference, parseOptionalDate("date", date), parseLines(lines), file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping(path = "/inspection-report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    ResponseEntity<ShipmentResponse> logInspectionReport(
            @PathVariable UUID purchaseOrderId,
            @RequestParam("reference") String reference,
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "outcome", required = false) String outcome,
            @RequestParam("file") MultipartFile file) {
        ShipmentResponse response = shipmentDocumentService.logInspectionReport(
            purchaseOrderId, reference, parseOptionalDate("date", date), outcome, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    ShipmentResponse get(@PathVariable UUID purchaseOrderId) {
        return shipmentDocumentService.getShipmentForPurchaseOrder(purchaseOrderId);
    }

    @GetMapping("/documents/{type}")
    ResponseEntity<byte[]> document(@PathVariable UUID purchaseOrderId, @PathVariable ShipmentDocumentType type) {
        byte[] bytes = shipmentDocumentService.getDocument(purchaseOrderId, type);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(bytes);
    }

    /** Packing-list line quantities arrive as a JSON array string in the multipart form; parse to a clean VALIDATION_ERROR. */
    private List<SkuQuantityRequest> parseLines(String lines) {
        if (lines == null || lines.isBlank()) {
            return List.of();
        }
        try {
            return List.of(objectMapper.readValue(lines, SkuQuantityRequest[].class));
        } catch (JsonProcessingException e) {
            throw new ValidationException("lines must be a JSON array of {skuId, quantity}");
        }
    }

    private static LocalDate parseDate(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field + " is required");
        }
        return parseOptionalDate(field, value);
    }

    private static LocalDate parseOptionalDate(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new ValidationException(field + " must be an ISO date (yyyy-MM-dd)");
        }
    }
}
