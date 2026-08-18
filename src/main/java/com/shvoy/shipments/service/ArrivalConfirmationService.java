package com.shvoy.shipments.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.CurrentUserContext;
import com.shvoy.ErrorCode;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.payments.event.ArrivalDiscrepancyEvent;
import com.shvoy.shipments.domain.ArrivalDiscrepancy;
import com.shvoy.shipments.domain.ArrivalDiscrepancyLine;
import com.shvoy.shipments.domain.GoodsReceiptLine;
import com.shvoy.shipments.domain.ReceiptStatus;
import com.shvoy.shipments.domain.ShipmentConsignment;
import com.shvoy.shipments.domain.ShipmentDocumentAuditEvent;
import com.shvoy.shipments.domain.ShipmentDocumentAuditEventType;
import com.shvoy.shipments.dto.ArrivalResponse;
import com.shvoy.shipments.dto.ArrivalResponse.ArrivalDiscrepancyLineResponse;
import com.shvoy.shipments.dto.ConfirmArrivalRequest;
import com.shvoy.shipments.dto.CorrectArrivalDateRequest;
import com.shvoy.shipments.dto.SkuQuantityRequest;
import com.shvoy.shipments.repository.ArrivalDiscrepancyLineRepository;
import com.shvoy.shipments.repository.ArrivalDiscrepancyRepository;
import com.shvoy.shipments.repository.GoodsReceiptLineRepository;
import com.shvoy.shipments.repository.ShipmentConsignmentRepository;
import com.shvoy.shipments.repository.ShipmentDocumentAuditEventRepository;
import com.shvoy.suppliers.domain.AnchorEvent;

/**
 * Story 7.6 — physical arrival confirmation, the second-stage action on a
 * consignment. Confirm goods arrived, <strong>checked against the provisional
 * GRN, never the original PO</strong>: the payment already matched against the
 * GRN (6.5), so the GRN is the settled expectation; re-deriving from the PO here
 * would re-litigate what the match settled.
 *
 * <p><strong>The governing principle: arrival never unwinds settled state.</strong>
 * Not the payment, not the match, not the closure, not the GRN. Arrival produces
 * exactly two things — a status, and (on a count mismatch) a discrepancy record
 * pointing into the credit lane. This service therefore never touches a payment,
 * never re-runs the match, and never re-assesses closure (which reads the GRN
 * lines, left untouched here — so a closed PO stays closed through an arrival
 * shortfall). The one cross-module signal is the {@code ARRIVAL} anchor (goods
 * arrived, both outcomes) and, on mismatch, a best-effort
 * {@link ArrivalDiscrepancyEvent} onto the resolver-lane seam.
 */
@Service
public class ArrivalConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(ArrivalConfirmationService.class);

    private final ShipmentConsignmentRepository consignmentRepository;
    private final GoodsReceiptLineRepository goodsReceiptLineRepository;
    private final ArrivalDiscrepancyRepository arrivalDiscrepancyRepository;
    private final ArrivalDiscrepancyLineRepository arrivalDiscrepancyLineRepository;
    private final ShipmentDocumentAuditEventRepository auditRepository;
    private final ShipmentAnchorPublisher anchorPublisher;
    private final ApplicationEventPublisher eventPublisher;

    ArrivalConfirmationService(ShipmentConsignmentRepository consignmentRepository,
            GoodsReceiptLineRepository goodsReceiptLineRepository,
            ArrivalDiscrepancyRepository arrivalDiscrepancyRepository,
            ArrivalDiscrepancyLineRepository arrivalDiscrepancyLineRepository,
            ShipmentDocumentAuditEventRepository auditRepository, ShipmentAnchorPublisher anchorPublisher,
            ApplicationEventPublisher eventPublisher) {
        this.consignmentRepository = consignmentRepository;
        this.goodsReceiptLineRepository = goodsReceiptLineRepository;
        this.arrivalDiscrepancyRepository = arrivalDiscrepancyRepository;
        this.arrivalDiscrepancyLineRepository = arrivalDiscrepancyLineRepository;
        this.auditRepository = auditRepository;
        this.anchorPublisher = anchorPublisher;
        this.eventPublisher = eventPublisher;
    }

    /** Confirm arrival against the GRN; the write commits, then the anchor (and any discrepancy event) publish. */
    public ArrivalResponse confirmArrival(UUID purchaseOrderId, ConfirmArrivalRequest request) {
        ArrivalOutcome outcome = record(purchaseOrderId, request);
        // Goods arrived regardless of whether counts matched — publish the ARRIVAL anchor for the affected PO.
        anchorPublisher.publishAll(List.of(new AnchorPublication(purchaseOrderId, AnchorEvent.ARRIVAL, request.arrivalDate())));
        if (outcome.discrepancyId() != null) {
            publishDiscrepancy(purchaseOrderId, outcome.consignmentId());
        }
        return outcome.response();
    }

    /** Correct a confirmed arrival's date — re-publishes the ARRIVAL anchor; counts/discrepancy stand. */
    public ArrivalResponse correctArrivalDate(UUID purchaseOrderId, CorrectArrivalDateRequest request) {
        ShipmentConsignment consignment = recordDateCorrection(purchaseOrderId, request);
        anchorPublisher.publishAll(List.of(new AnchorPublication(purchaseOrderId, AnchorEvent.ARRIVAL, request.arrivalDate())));
        return getArrival(purchaseOrderId, consignment);
    }

    @Transactional(readOnly = true)
    public ArrivalResponse getArrival(UUID purchaseOrderId) {
        return getArrival(purchaseOrderId, findArrivalEligibleOrConfirmed(purchaseOrderId));
    }

    // --- the transactional writes (commit before publishing, so payments reacts to durable data) ---

    @Transactional
    ArrivalOutcome record(UUID purchaseOrderId, ConfirmArrivalRequest request) {
        ShipmentConsignment consignment = findActiveConsignment(purchaseOrderId);
        if (consignment.getReceiptStatus() != ReceiptStatus.PROVISIONALLY_RECEIPTED) {
            // No GRN yet, or already arrival-confirmed — distinct stable codes.
            if (consignment.getReceiptStatus() == ReceiptStatus.ARRIVED_CONFIRMED
                    || consignment.getReceiptStatus() == ReceiptStatus.ARRIVED_WITH_DISCREPANCY) {
                throw new ConflictException(ErrorCode.ARRIVAL_ALREADY_CONFIRMED,
                    "Arrival is already confirmed for this consignment (" + consignment.getReceiptStatus() + ")");
            }
            throw new ConflictException(ErrorCode.CONSIGNMENT_NOT_ARRIVAL_ELIGIBLE,
                "Consignment is not provisionally receipted — nothing to confirm arrival against ("
                    + consignment.getReceiptStatus() + ")");
        }

        Map<UUID, Integer> grn = grnQuantities(consignment.getId());
        Map<UUID, Integer> arrived = new HashMap<>();
        for (SkuQuantityRequest line : request.arrivedLines()) {
            arrived.merge(line.skuId(), line.quantity(), Integer::sum);
        }

        // Compare strictly vs the GRN snapshot, per SKU — the PO is NOT consulted (the design stance).
        List<ArrivalDiscrepancyLine> deltas = new ArrayList<>();
        Set<UUID> skus = new LinkedHashSet<>(grn.keySet());
        skus.addAll(arrived.keySet());
        boolean matched = true;
        for (UUID sku : skus) {
            int expected = grn.getOrDefault(sku, 0);
            int got = arrived.getOrDefault(sku, 0);
            if (expected != got) {
                matched = false;
            }
        }

        consignment.confirmArrival(request.arrivalDate(), matched);
        consignmentRepository.save(consignment);

        UUID discrepancyId = null;
        List<ArrivalDiscrepancyLineResponse> lineResponses = new ArrayList<>();
        if (!matched) {
            ArrivalDiscrepancy discrepancy = arrivalDiscrepancyRepository.save(
                new ArrivalDiscrepancy(consignment.getId(), purchaseOrderId, request.arrivalDate()));
            discrepancyId = discrepancy.getId();
            for (UUID sku : skus) {
                int expected = grn.getOrDefault(sku, 0);
                int got = arrived.getOrDefault(sku, 0);
                if (expected != got) {
                    ArrivalDiscrepancyLine line = arrivalDiscrepancyLineRepository.save(
                        new ArrivalDiscrepancyLine(discrepancy.getId(), sku, expected, got));
                    lineResponses.add(new ArrivalDiscrepancyLineResponse(
                        sku, expected, got, line.getDirection().name()));
                }
            }
            audit(consignment, purchaseOrderId, ShipmentDocumentAuditEventType.ARRIVAL_DISCREPANCY_RAISED,
                "Arrival confirmed " + request.arrivalDate() + " with a count discrepancy vs the GRN: "
                    + describe(lineResponses) + " — a credit-lane matter (payment untouched)");
        } else {
            audit(consignment, purchaseOrderId, ShipmentDocumentAuditEventType.ARRIVAL_CONFIRMED,
                "Arrival confirmed " + request.arrivalDate() + " — counts matched the provisional GRN");
        }

        ArrivalResponse response = new ArrivalResponse(consignment.getId(), purchaseOrderId,
            consignment.getReceiptStatus(), consignment.getArrivalDate(), discrepancyId, lineResponses);
        return new ArrivalOutcome(consignment.getId(), discrepancyId, response);
    }

    @Transactional
    ShipmentConsignment recordDateCorrection(UUID purchaseOrderId, CorrectArrivalDateRequest request) {
        ShipmentConsignment consignment = findActiveConsignment(purchaseOrderId);
        if (consignment.getReceiptStatus() != ReceiptStatus.ARRIVED_CONFIRMED
                && consignment.getReceiptStatus() != ReceiptStatus.ARRIVED_WITH_DISCREPANCY) {
            throw new ConflictException(ErrorCode.ARRIVAL_NOT_CONFIRMED,
                "Arrival is not yet confirmed for this consignment (" + consignment.getReceiptStatus() + ")");
        }
        LocalDate previous = consignment.getArrivalDate();
        consignment.correctArrivalDate(request.arrivalDate());
        consignmentRepository.save(consignment);
        audit(consignment, purchaseOrderId, ShipmentDocumentAuditEventType.ARRIVAL_DATE_CORRECTED,
            "Arrival date corrected " + previous + " → " + request.arrivalDate() + " — ARRIVAL anchor re-published");
        return consignment;
    }

    // --- helpers ---

    private void publishDiscrepancy(UUID purchaseOrderId, UUID consignmentId) {
        try {
            eventPublisher.publishEvent(new ArrivalDiscrepancyEvent(purchaseOrderId, consignmentId));
        } catch (RuntimeException e) {
            log.warn("Arrival-discrepancy publish failed for PO {} — the discrepancy record remains", purchaseOrderId, e);
        }
    }

    private ArrivalResponse getArrival(UUID purchaseOrderId, ShipmentConsignment consignment) {
        Optional<ArrivalDiscrepancy> discrepancy = arrivalDiscrepancyRepository.findAll().stream()
            .filter(d -> d.getConsignmentId().equals(consignment.getId()))
            .findFirst();
        List<ArrivalDiscrepancyLineResponse> lines = discrepancy.map(d -> arrivalDiscrepancyLineRepository.findAll().stream()
                .filter(l -> l.getArrivalDiscrepancyId().equals(d.getId()))
                .map(l -> new ArrivalDiscrepancyLineResponse(
                    l.getSkuId(), l.getExpectedQuantity(), l.getArrivedQuantity(), l.getDirection().name()))
                .toList())
            .orElse(List.of());
        return new ArrivalResponse(consignment.getId(), purchaseOrderId, consignment.getReceiptStatus(),
            consignment.getArrivalDate(), discrepancy.map(ArrivalDiscrepancy::getId).orElse(null), lines);
    }

    private Map<UUID, Integer> grnQuantities(UUID consignmentId) {
        Map<UUID, Integer> quantities = new HashMap<>();
        for (GoodsReceiptLine line : goodsReceiptLineRepository.findAll()) {
            if (line.getConsignmentId().equals(consignmentId)) {
                quantities.merge(line.getSkuId(), line.getReceivedQuantity(), Integer::sum);
            }
        }
        return quantities;
    }

    private ShipmentConsignment findActiveConsignment(UUID purchaseOrderId) {
        ShipmentConsignment consignment = consignmentRepository.findAll().stream()
            .filter(c -> c.getPurchaseOrderId().equals(purchaseOrderId) && !c.isDetached())
            .findFirst()
            .orElseThrow(() -> new NotFoundException("No shipment for this purchase order"));
        TenantGuard.assertOwned(consignment);
        return consignment;
    }

    private ShipmentConsignment findArrivalEligibleOrConfirmed(UUID purchaseOrderId) {
        return findActiveConsignment(purchaseOrderId);
    }

    private void audit(ShipmentConsignment consignment, UUID purchaseOrderId, ShipmentDocumentAuditEventType type,
            String detail) {
        auditRepository.save(new ShipmentDocumentAuditEvent(
            consignment.getShipmentId(), consignment.getId(), purchaseOrderId, type, detail, CurrentUserContext.get()));
    }

    private static String describe(List<ArrivalDiscrepancyLineResponse> lines) {
        StringBuilder sb = new StringBuilder();
        for (ArrivalDiscrepancyLineResponse line : lines) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(line.skuId()).append(" expected ").append(line.expectedQuantity())
                .append(" arrived ").append(line.arrivedQuantity()).append(" (").append(line.direction()).append(")");
        }
        return sb.toString();
    }

    /** Internal carrier from the committed write to the post-commit publish step. */
    record ArrivalOutcome(UUID consignmentId, UUID discrepancyId, ArrivalResponse response) {
    }
}
