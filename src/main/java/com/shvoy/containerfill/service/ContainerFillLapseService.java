package com.shvoy.containerfill.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.CurrentUserContext;
import com.shvoy.EmailContent;
import com.shvoy.EmailMessage;
import com.shvoy.EmailSender;
import com.shvoy.EmailSource;
import com.shvoy.TenantGuard;
import com.shvoy.containerfill.domain.ContainerFillOffer;
import com.shvoy.containerfill.domain.ContainerFillOfferAuditEvent;
import com.shvoy.containerfill.domain.ContainerFillOfferAuditEventType;
import com.shvoy.containerfill.domain.ContainerFillOfferStatus;
import com.shvoy.containerfill.repository.ContainerFillOfferAuditEventRepository;
import com.shvoy.containerfill.repository.ContainerFillOfferRepository;
import com.shvoy.onboarding.service.UserDirectoryService;
import com.shvoy.shipments.service.ShipmentAccessService;
import com.shvoy.suppliers.service.SupplierService;

/**
 * Lapses a single overdue container-fill offer (Story 8.3) — the poll's second
 * check, mirroring {@link ContainerFillReminderService}. Runs inside the tenant
 * context the poll set, as its own short transaction (one connection).
 *
 * <p>A guard (status still {@code AWAITING_DECISION}) settles the decide-vs-lapse
 * race: a human who confirmed/declined at the boundary already flipped the status,
 * so the poll skips. The lapse notification (consumer six) then closes the loop the
 * reminder opened; a send failure follows 9.4's law (recorded, never throws).
 */
@Service
public class ContainerFillLapseService {

    private final ContainerFillOfferRepository offerRepository;
    private final ContainerFillOfferAuditEventRepository auditRepository;
    private final UserDirectoryService userDirectoryService;
    private final SupplierService supplierService;
    private final ShipmentAccessService shipmentAccessService;
    private final ContainerFillLapseEmailComposer composer;
    private final EmailSender emailSender;

    ContainerFillLapseService(ContainerFillOfferRepository offerRepository,
            ContainerFillOfferAuditEventRepository auditRepository, UserDirectoryService userDirectoryService,
            SupplierService supplierService, ShipmentAccessService shipmentAccessService,
            ContainerFillLapseEmailComposer composer, EmailSender emailSender) {
        this.offerRepository = offerRepository;
        this.auditRepository = auditRepository;
        this.userDirectoryService = userDirectoryService;
        this.supplierService = supplierService;
        this.shipmentAccessService = shipmentAccessService;
        this.composer = composer;
        this.emailSender = emailSender;
    }

    @Transactional
    public void lapse(UUID offerId) {
        ContainerFillOffer offer = offerRepository.findById(offerId).orElse(null);
        if (offer == null) {
            return;
        }
        TenantGuard.assertOwned(offer);
        if (offer.getStatus() != ContainerFillOfferStatus.AWAITING_DECISION) {
            return; // decided at the boundary between the poll's snapshot and now — the decision wins
        }

        offer.lapse();
        offerRepository.save(offer);
        audit(offer, "Deadline " + offer.getDeadline() + " passed undecided — lapsed (shipped without)");

        List<String> recipients = userDirectoryService.resolvePurchasingAndAdminEmails();
        if (recipients.isEmpty()) {
            return;
        }
        String supplierName = supplierService.getSummary(offer.getSupplierId()).name();
        String blReference = shipmentAccessService.blReferenceOf(offer.getShipmentId()).orElse(null);
        EmailContent content = composer.compose(offer.getSpareCbm(), supplierName, blReference, offer.getId());
        for (String recipient : recipients) {
            emailSender.send(new EmailMessage(recipient, content.subject(), content.body(),
                EmailSource.CONTAINER_FILL_LAPSED, offer.getId().toString()));
        }
    }

    private void audit(ContainerFillOffer offer, String detail) {
        auditRepository.save(new ContainerFillOfferAuditEvent(
            offer.getId(), ContainerFillOfferAuditEventType.LAPSED, detail, CurrentUserContext.getOrNull()));
    }
}
