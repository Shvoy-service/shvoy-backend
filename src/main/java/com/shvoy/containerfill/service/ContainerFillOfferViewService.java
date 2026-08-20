package com.shvoy.containerfill.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.containerfill.domain.ContainerFillOffer;
import com.shvoy.containerfill.dto.ContainerFillOfferResponse;
import com.shvoy.containerfill.dto.ContainerFillOfferSummary;
import com.shvoy.containerfill.repository.ContainerFillOfferRepository;
import com.shvoy.shipments.service.ShipmentAccessService;
import com.shvoy.suppliers.service.SupplierService;

/**
 * The read side of container-fill offers (Story 8.1). The default list is the
 * <em>undecided</em> queue (the operational surface — OPEN/AWAITING_DECISION);
 * {@code includeDecided} widens it. Shipment (BL) and supplier context are joined
 * at read time from their modules, never persisted on the offer.
 */
@Service
public class ContainerFillOfferViewService {

    private final ContainerFillOfferRepository offerRepository;
    private final ShipmentAccessService shipmentAccessService;
    private final SupplierService supplierService;

    ContainerFillOfferViewService(ContainerFillOfferRepository offerRepository,
            ShipmentAccessService shipmentAccessService, SupplierService supplierService) {
        this.offerRepository = offerRepository;
        this.shipmentAccessService = shipmentAccessService;
        this.supplierService = supplierService;
    }

    @Transactional(readOnly = true)
    public List<ContainerFillOfferSummary> list(boolean includeDecided) {
        return offerRepository.findAll().stream()
            .filter(offer -> includeDecided || offer.isUndecided())
            .sorted(Comparator.comparing(ContainerFillOffer::getCreatedAt).reversed())
            .map(this::toSummary)
            .toList();
    }

    @Transactional(readOnly = true)
    public ContainerFillOfferResponse getView(UUID offerId) {
        ContainerFillOffer offer = offerRepository.findById(offerId)
            .orElseThrow(() -> new NotFoundException("Container-fill offer not found"));
        TenantGuard.assertOwned(offer);
        return toResponse(offer);
    }

    private ContainerFillOfferSummary toSummary(ContainerFillOffer offer) {
        return new ContainerFillOfferSummary(
            offer.getId(),
            offer.getShipmentId(),
            shipmentAccessService.blReferenceOf(offer.getShipmentId()).orElse(null),
            offer.getSupplierId(),
            supplierService.getSummary(offer.getSupplierId()).name(),
            offer.getSpareCbm(),
            offer.getStatus(),
            offer.getDeadline(),
            offer.getCreatedAt());
    }

    private ContainerFillOfferResponse toResponse(ContainerFillOffer offer) {
        int otherUndecided = (int) offerRepository.findAll().stream()
            .filter(other -> other.getShipmentId().equals(offer.getShipmentId()))
            .filter(other -> !other.getId().equals(offer.getId()))
            .filter(ContainerFillOffer::isUndecided)
            .count();
        return new ContainerFillOfferResponse(
            offer.getId(),
            offer.getShipmentId(),
            shipmentAccessService.blReferenceOf(offer.getShipmentId()).orElse(null),
            offer.getSupplierId(),
            supplierService.getSummary(offer.getSupplierId()).name(),
            offer.getSpareCbm(),
            offer.getStatus(),
            offer.getDeadline(),
            offer.getReminderSentAt(),
            offer.getNotes(),
            offer.getFlaggedBy(),
            offer.getCreatedAt(),
            offer.getUpdatedAt(),
            otherUndecided);
    }
}
