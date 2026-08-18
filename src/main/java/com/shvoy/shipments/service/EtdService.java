package com.shvoy.shipments.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.CurrentUserContext;
import com.shvoy.ErrorCode;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.purchaseorders.service.PurchaseOrderService;
import com.shvoy.shipments.domain.EtdRevision;
import com.shvoy.shipments.domain.ReceiptStatus;
import com.shvoy.shipments.domain.ShipmentConsignment;
import com.shvoy.shipments.dto.EtdResponse;
import com.shvoy.shipments.dto.EtdResponse.EtdRevisionResponse;
import com.shvoy.shipments.dto.SetConfirmedEtdRequest;
import com.shvoy.shipments.repository.EtdRevisionRepository;
import com.shvoy.shipments.repository.ShipmentConsignmentRepository;

/**
 * Story 7.5 — ETD tracking. Records the supplier's confirmed ETD against the
 * PO's requested ETD, surfaces the signed delta, and keeps the history when it
 * shifts. The last story in Feature 7. In the {@code shipments} module.
 *
 * <p><strong>ETD is not an anchor.</strong> An estimated departure informs
 * logistics, not payment timing — this service publishes no anchor event and has
 * no payment interaction, deliberately. It is provably inert beyond its own
 * fields (asserted by test).
 *
 * <p>A confirmed ETD can arrive weeks ahead of any document (a supplier confirms
 * a ready date before the BL), so — unlike most consignment data — it doesn't
 * gate on document state, and logging one counts as <strong>first shipment
 * contact</strong>: it creates the shipment/consignment via the shared
 * {@link ConsignmentProvisioningService} if none exists. Revisions are the norm
 * (ETDs slip); every change appends an {@link EtdRevision} — the history is the
 * audit, and the substrate Phase 2's proactive chasing will read.
 */
@Service
public class EtdService {

    private final ConsignmentProvisioningService provisioningService;
    private final ShipmentConsignmentRepository consignmentRepository;
    private final EtdRevisionRepository etdRevisionRepository;
    private final PurchaseOrderService purchaseOrderService;

    EtdService(ConsignmentProvisioningService provisioningService, ShipmentConsignmentRepository consignmentRepository,
            EtdRevisionRepository etdRevisionRepository, PurchaseOrderService purchaseOrderService) {
        this.provisioningService = provisioningService;
        this.consignmentRepository = consignmentRepository;
        this.etdRevisionRepository = etdRevisionRepository;
        this.purchaseOrderService = purchaseOrderService;
    }

    /** Set or revise the confirmed ETD — first-touch-creates if needed, pre-arrival only, appends to history. */
    @Transactional
    public EtdResponse setConfirmedEtd(UUID purchaseOrderId, SetConfirmedEtdRequest request) {
        ShipmentConsignment consignment = provisioningService.resolveOrCreate(purchaseOrderId);
        if (consignment.getReceiptStatus() == ReceiptStatus.ARRIVED_CONFIRMED
                || consignment.getReceiptStatus() == ReceiptStatus.ARRIVED_WITH_DISCREPANCY) {
            throw new ConflictException(ErrorCode.ETD_NOT_SETTABLE_AFTER_ARRIVAL,
                "The goods have already arrived (" + consignment.getReceiptStatus() + ") — a confirmed ETD is moot");
        }
        consignment.setConfirmedEtd(request.confirmedEtd());
        consignmentRepository.save(consignment);
        etdRevisionRepository.save(new EtdRevision(consignment.getId(), purchaseOrderId,
            request.confirmedEtd(), request.reason(), CurrentUserContext.get()));
        return getEtd(purchaseOrderId);
    }

    @Transactional(readOnly = true)
    public EtdResponse getEtd(UUID purchaseOrderId) {
        ShipmentConsignment consignment = findConsignment(purchaseOrderId);
        LocalDate requestedEtd = purchaseOrderService.getRequestedEtd(purchaseOrderId).orElse(null);
        LocalDate confirmedEtd = consignment.getConfirmedEtd();
        Integer deltaDays = delta(requestedEtd, confirmedEtd);

        List<EtdRevisionResponse> history = etdRevisionRepository.findAll().stream()
            .filter(r -> r.getConsignmentId().equals(consignment.getId()))
            .sorted(Comparator.comparing(EtdRevision::getChangedAt).reversed())
            .map(r -> new EtdRevisionResponse(r.getConfirmedEtd(), r.getReason(), r.getChangedBy(), r.getChangedAt()))
            .toList();

        return new EtdResponse(purchaseOrderId, requestedEtd, confirmedEtd, deltaDays, confirmedEtd == null, history);
    }

    /**
     * The delta, signed days: {@code confirmed − requested}. Positive = later
     * than requested (the slip), negative = earlier. Null when either date is
     * absent — shown honestly as "awaiting confirmation", never a fake zero.
     */
    static Integer delta(LocalDate requestedEtd, LocalDate confirmedEtd) {
        if (requestedEtd == null || confirmedEtd == null) {
            return null;
        }
        return (int) ChronoUnit.DAYS.between(requestedEtd, confirmedEtd);
    }

    private ShipmentConsignment findConsignment(UUID purchaseOrderId) {
        Optional<ShipmentConsignment> consignment = consignmentRepository.findAll().stream()
            .filter(c -> c.getPurchaseOrderId().equals(purchaseOrderId) && !c.isDetached())
            .findFirst();
        ShipmentConsignment found = consignment.orElseThrow(() -> new NotFoundException("No shipment for this purchase order"));
        TenantGuard.assertOwned(found);
        return found;
    }
}
