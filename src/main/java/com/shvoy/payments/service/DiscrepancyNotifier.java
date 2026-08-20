package com.shvoy.payments.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.shvoy.EmailContent;
import com.shvoy.EmailMessage;
import com.shvoy.EmailSource;
import com.shvoy.EmailSender;
import com.shvoy.onboarding.service.UserDirectoryService;

/**
 * Notifies the discrepancy resolvers that a payment is blocked and needs
 * resolution (Story 6.6), through the shared {@link EmailSender} seam (4.7) —
 * the <strong>fourth</strong> consumer after invites, PO send, and approvals.
 * It addresses the claimable queue — every active {@code PURCHASING}/{@code
 * ADMIN} user — since no one is assigned until someone claims (a claimed →
 * claimer notification has no trigger yet: the only notification is at open
 * time, when nothing is claimed). Content and the case link are the composer's.
 */
@Service
class DiscrepancyNotifier {

    private static final Logger log = LoggerFactory.getLogger(DiscrepancyNotifier.class);

    private final UserDirectoryService userDirectoryService;
    private final DiscrepancyEmailComposer discrepancyEmailComposer;
    private final EmailSender emailSender;

    DiscrepancyNotifier(UserDirectoryService userDirectoryService,
            DiscrepancyEmailComposer discrepancyEmailComposer, EmailSender emailSender) {
        this.userDirectoryService = userDirectoryService;
        this.discrepancyEmailComposer = discrepancyEmailComposer;
        this.emailSender = emailSender;
    }

    void notifyOpened(String poNumber, String failureDetail, UUID caseId) {
        List<String> recipients = userDirectoryService.resolveDiscrepancyResolverEmails();
        if (recipients.isEmpty()) {
            log.info("Discrepancy opened for PO {}, but there are no active PURCHASING/ADMIN users to notify", poNumber);
            return;
        }
        EmailContent content = discrepancyEmailComposer.compose(poNumber, failureDetail, caseId);
        for (String recipient : recipients) {
            emailSender.send(new EmailMessage(
                recipient, content.subject(), content.body(),
                EmailSource.DISCREPANCY, poNumber));
        }
    }
}
