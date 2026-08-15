package com.shvoy.payments.service;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.shvoy.ConflictException;
import com.shvoy.ErrorCode;
import com.shvoy.ValidationException;
import com.shvoy.payments.domain.PaymentType;
import com.shvoy.payments.dto.LogInvoiceRequest;
import com.shvoy.payments.repository.GrnProjectionLineRepository;
import com.shvoy.payments.repository.PaymentRepository;
import com.shvoy.purchaseorders.dto.PurchaseOrderReconciliationLine;
import com.shvoy.purchaseorders.service.PurchaseOrderService;

/**
 * Validates that an invoice's declared coverage is <em>well-formed</em> at
 * entry (invoice remodel) — its type-dependent references exist, belong to this
 * PO, and are coherent with the PO's shape. This is existence/ownership only,
 * the same "record faithfully, judge later" posture as the rest of invoice
 * logging: whether the amount agrees with what it claims to cover is NOT checked
 * here — that's the match's job (6.5).
 *
 * <ul>
 *   <li>{@code DEPOSIT} — the PO must actually carry a deposit obligation (a
 *       {@code DEPOSIT} payment exists); a deposit invoice against a
 *       zero-deposit / rolling PO is incoherent.</li>
 *   <li>{@code BALANCE} — the PO must carry a balance obligation (always true
 *       for a scheduled PO; the check rejects a balance invoice against a PO
 *       with no generated balance payment).</li>
 *   <li>{@code SHIPMENT} — a consignment reference is required and must name a
 *       consignment that has been receipted against this PO (present in the GRN
 *       projection). The strongest reconciliation signal.</li>
 *   <li>{@code LINES} — at least one covered line, each naming a SKU that
 *       belongs to the PO.</li>
 *   <li>{@code AMOUNT} — the free-standing fallback; nothing to validate, and
 *       deliberately never rejected (it's flagged as the weakest signal on the
 *       response instead).</li>
 * </ul>
 */
@Component
class InvoiceCoverageValidator {

    private final PaymentRepository paymentRepository;
    private final GrnProjectionLineRepository grnProjectionLineRepository;
    private final PurchaseOrderService purchaseOrderService;

    InvoiceCoverageValidator(PaymentRepository paymentRepository,
            GrnProjectionLineRepository grnProjectionLineRepository, PurchaseOrderService purchaseOrderService) {
        this.paymentRepository = paymentRepository;
        this.grnProjectionLineRepository = grnProjectionLineRepository;
        this.purchaseOrderService = purchaseOrderService;
    }

    void validate(UUID purchaseOrderId, LogInvoiceRequest request) {
        switch (request.coversType()) {
            case DEPOSIT -> requirePaymentOfType(purchaseOrderId, PaymentType.DEPOSIT,
                "a DEPOSIT invoice requires the PO to carry a deposit obligation");
            case BALANCE -> requirePaymentOfType(purchaseOrderId, PaymentType.BALANCE,
                "a BALANCE invoice requires the PO to carry a balance obligation");
            case SHIPMENT -> validateShipment(purchaseOrderId, request);
            case LINES -> validateLines(purchaseOrderId, request);
            case AMOUNT -> { /* fallback: accepted as-is, flagged weakest on the response */ }
        }
    }

    private void requirePaymentOfType(UUID purchaseOrderId, PaymentType type, String message) {
        boolean exists = paymentRepository.findAll().stream()
            .anyMatch(p -> p.getPurchaseOrderId().equals(purchaseOrderId) && p.getType() == type);
        if (!exists) {
            throw new ConflictException(ErrorCode.INVOICE_COVERAGE_INCOHERENT, message);
        }
    }

    private void validateShipment(UUID purchaseOrderId, LogInvoiceRequest request) {
        UUID consignmentId = request.coversConsignmentId();
        if (consignmentId == null) {
            throw new ValidationException("coversConsignmentId: required for a SHIPMENT invoice");
        }
        boolean receipted = grnProjectionLineRepository.findAll().stream()
            .anyMatch(l -> l.getPurchaseOrderId().equals(purchaseOrderId) && l.getConsignmentId().equals(consignmentId));
        if (!receipted) {
            throw new ConflictException(ErrorCode.INVOICE_COVERAGE_INCOHERENT,
                "a SHIPMENT invoice must name a consignment receipted against this PO");
        }
    }

    private void validateLines(UUID purchaseOrderId, LogInvoiceRequest request) {
        if (request.coveredLines() == null || request.coveredLines().isEmpty()) {
            throw new ValidationException("coveredLines: at least one line is required for a LINES invoice");
        }
        Set<UUID> poSkus = purchaseOrderService.getReconciliationView(purchaseOrderId).lines().stream()
            .map(PurchaseOrderReconciliationLine::skuId)
            .collect(Collectors.toSet());
        boolean allBelong = request.coveredLines().stream().allMatch(l -> poSkus.contains(l.skuId()));
        if (!allBelong) {
            throw new ConflictException(ErrorCode.INVOICE_COVERAGE_INCOHERENT,
                "a LINES invoice may only claim SKUs that belong to the PO");
        }
    }
}
