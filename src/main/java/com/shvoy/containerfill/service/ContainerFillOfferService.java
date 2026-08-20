package com.shvoy.containerfill.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.CurrentUserContext;
import com.shvoy.ErrorCode;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.containerfill.domain.ContainerFillOffer;
import com.shvoy.containerfill.domain.ContainerFillOfferAuditEvent;
import com.shvoy.containerfill.domain.ContainerFillOfferAuditEventType;
import com.shvoy.containerfill.domain.ContainerFillOfferStatus;
import com.shvoy.containerfill.dto.CancelContainerFillOfferRequest;
import com.shvoy.containerfill.dto.FlagContainerFillOfferRequest;
import com.shvoy.containerfill.dto.SetContainerFillDeadlineRequest;
import com.shvoy.containerfill.repository.ContainerFillOfferAuditEventRepository;
import com.shvoy.containerfill.repository.ContainerFillOfferRepository;
import com.shvoy.shipments.service.ShipmentAccessService;
import com.shvoy.suppliers.service.SupplierService;

/**
 * The write side of container-fill offers (Story 8.1) — flag a supplier's spare
 * capacity, and withdraw an undecided offer (cancel-and-relog). The offer attaches
 * to a real container: an early offer resolves-or-creates the shipment via the
 * shared first-touch path (keyed by PO, exactly as 7.5's ETD does), then the
 * container must not have fully arrived. Every transition is audited.
 */
@Service
public class ContainerFillOfferService {

    private final ShipmentAccessService shipmentAccessService;
    private final SupplierService supplierService;
    private final ContainerFillOfferRepository offerRepository;
    private final ContainerFillOfferAuditEventRepository auditRepository;

    ContainerFillOfferService(ShipmentAccessService shipmentAccessService, SupplierService supplierService,
            ContainerFillOfferRepository offerRepository, ContainerFillOfferAuditEventRepository auditRepository) {
        this.shipmentAccessService = shipmentAccessService;
        this.supplierService = supplierService;
        this.offerRepository = offerRepository;
        this.auditRepository = auditRepository;
    }

    /** Record a supplier's spare-capacity offer against a PO's container. Returns the new offer's id. */
    @Transactional
    public UUID flag(UUID purchaseOrderId, FlagContainerFillOfferRequest request) {
        UUID shipmentId = shipmentAccessService.resolveOrCreateShipment(purchaseOrderId);
        if (shipmentAccessService.isFullyArrived(shipmentId)) {
            throw new ConflictException(ErrorCode.CONTAINER_FILL_OFFER_AFTER_ARRIVAL,
                "Cannot flag spare capacity on a container that has already fully arrived");
        }
        supplierService.assertOwnSupplierExists(request.supplierId());

        ContainerFillOffer offer = offerRepository.save(new ContainerFillOffer(
            shipmentId, request.supplierId(), request.spareCbm(), request.notes(), CurrentUserContext.get()));
        audit(offer, ContainerFillOfferAuditEventType.FLAGGED,
            "Flagged " + request.spareCbm() + " CBM spare on shipment " + shipmentId);
        return offer.getId();
    }

    /** Withdraw an undecided offer (corrections are cancel-and-relog — never a silent CBM edit on a live offer). */
    @Transactional
    public void cancel(UUID offerId, CancelContainerFillOfferRequest request) {
        ContainerFillOffer offer = findOwnOffer(offerId);
        if (!offer.isCancellable()) {
            throw new ConflictException(ErrorCode.CONTAINER_FILL_OFFER_NOT_CANCELLABLE,
                "Container-fill offer is not cancellable (status " + offer.getStatus() + ")");
        }
        offer.cancel();
        offerRepository.save(offer);
        audit(offer, ContainerFillOfferAuditEventType.CANCELLED, "Cancelled: " + request.reason());
    }

    /**
     * Set the decision deadline (Story 8.2). On an {@code OPEN} offer this is the
     * first deadline (→ {@code AWAITING_DECISION}); on an {@code AWAITING_DECISION}
     * offer it's a renegotiation. Either way the deadline must be in the future and
     * the reminder is re-armed (its stamp cleared). A decided/lapsed/cancelled offer
     * takes no deadline.
     */
    @Transactional
    public void setDeadline(UUID offerId, SetContainerFillDeadlineRequest request) {
        ContainerFillOffer offer = findOwnOffer(offerId);
        ContainerFillOfferStatus status = offer.getStatus();
        if (status != ContainerFillOfferStatus.OPEN && status != ContainerFillOfferStatus.AWAITING_DECISION) {
            throw new ConflictException(ErrorCode.CONTAINER_FILL_DEADLINE_NOT_SETTABLE,
                "Cannot set a decision deadline on a container-fill offer in status " + status);
        }
        if (!request.deadline().isAfter(Instant.now())) {
            throw new ConflictException(ErrorCode.CONTAINER_FILL_DEADLINE_IN_PAST,
                "The decision deadline must be in the future");
        }

        if (status == ContainerFillOfferStatus.OPEN) {
            offer.setDeadline(request.deadline());
            offerRepository.save(offer);
            audit(offer, ContainerFillOfferAuditEventType.DEADLINE_SET,
                "Deadline set to " + request.deadline() + reasonSuffix(request.reason()));
        } else {
            Instant previous = offer.getDeadline();
            offer.reviseDeadline(request.deadline());
            offerRepository.save(offer);
            audit(offer, ContainerFillOfferAuditEventType.DEADLINE_REVISED,
                "Deadline revised from " + previous + " to " + request.deadline() + reasonSuffix(request.reason()));
        }
    }

    private static String reasonSuffix(String reason) {
        return reason == null || reason.isBlank() ? "" : " (reason: " + reason + ")";
    }

    private ContainerFillOffer findOwnOffer(UUID offerId) {
        ContainerFillOffer offer = offerRepository.findById(offerId)
            .orElseThrow(() -> new NotFoundException("Container-fill offer not found"));
        TenantGuard.assertOwned(offer);
        return offer;
    }

    private void audit(ContainerFillOffer offer, ContainerFillOfferAuditEventType type, String detail) {
        auditRepository.save(new ContainerFillOfferAuditEvent(
            offer.getId(), type, detail, CurrentUserContext.getOrNull()));
    }
}
