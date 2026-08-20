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
 * Sends the one approaching-deadline reminder for a single container-fill offer
 * (Story 8.2). Runs inside the tenant context the poll set for this offer's
 * company — a separate transactional bean so the proxy applies and each offer is
 * its own short transaction (one connection, no {@code REQUIRES_NEW} nesting).
 *
 * <p><strong>Idempotence:</strong> stamp {@code reminder_sent_at} first, then send.
 * The real {@link EmailSender} never throws (9.4's failure-isolation law → the
 * send is recorded, best-effort), so a normal run commits a single reminder. But
 * if anything here throws before commit, the transaction rolls back and the stamp
 * reverts to null — so the next poll retries naturally. A guard also skips an offer
 * already reminded or no longer {@code AWAITING_DECISION} (a stale poll snapshot or
 * a redeploy re-run can't double-send).
 */
@Service
public class ContainerFillReminderService {

    private final ContainerFillOfferRepository offerRepository;
    private final ContainerFillOfferAuditEventRepository auditRepository;
    private final UserDirectoryService userDirectoryService;
    private final SupplierService supplierService;
    private final ShipmentAccessService shipmentAccessService;
    private final ContainerFillReminderEmailComposer composer;
    private final EmailSender emailSender;

    ContainerFillReminderService(ContainerFillOfferRepository offerRepository,
            ContainerFillOfferAuditEventRepository auditRepository, UserDirectoryService userDirectoryService,
            SupplierService supplierService, ShipmentAccessService shipmentAccessService,
            ContainerFillReminderEmailComposer composer, EmailSender emailSender) {
        this.offerRepository = offerRepository;
        this.auditRepository = auditRepository;
        this.userDirectoryService = userDirectoryService;
        this.supplierService = supplierService;
        this.shipmentAccessService = shipmentAccessService;
        this.composer = composer;
        this.emailSender = emailSender;
    }

    @Transactional
    public void sendReminder(UUID offerId) {
        ContainerFillOffer offer = offerRepository.findById(offerId).orElse(null);
        if (offer == null) {
            return;
        }
        TenantGuard.assertOwned(offer);
        if (offer.getStatus() != ContainerFillOfferStatus.AWAITING_DECISION || offer.getReminderSentAt() != null) {
            return; // decided/revised/already-reminded between the poll's snapshot and now
        }

        // Stamp first, in this transaction: a failure below rolls it back → the next poll retries.
        offer.markReminderSent();
        offerRepository.save(offer);

        List<String> recipients = userDirectoryService.resolvePurchasingAndAdminEmails();
        if (recipients.isEmpty()) {
            audit(offer, "Reminder due but no active PURCHASING/ADMIN users to notify");
            return;
        }

        String supplierName = supplierService.getSummary(offer.getSupplierId()).name();
        String blReference = shipmentAccessService.blReferenceOf(offer.getShipmentId()).orElse(null);
        EmailContent content = composer.compose(
            offer.getSpareCbm(), supplierName, blReference, offer.getDeadline(), offer.getId());
        for (String recipient : recipients) {
            emailSender.send(new EmailMessage(recipient, content.subject(), content.body(),
                EmailSource.CONTAINER_FILL_REMINDER, offer.getId().toString()));
        }
        audit(offer, "Reminder sent to " + recipients.size() + " recipient(s), deadline " + offer.getDeadline());
    }

    private void audit(ContainerFillOffer offer, String detail) {
        auditRepository.save(new ContainerFillOfferAuditEvent(
            offer.getId(), ContainerFillOfferAuditEventType.REMINDER_SENT, detail, CurrentUserContext.getOrNull()));
    }
}
