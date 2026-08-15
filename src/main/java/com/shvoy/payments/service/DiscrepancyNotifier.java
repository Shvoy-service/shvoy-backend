package com.shvoy.payments.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.shvoy.EmailMessage;
import com.shvoy.EmailSender;
import com.shvoy.onboarding.service.UserDirectoryService;

/**
 * Notifies the discrepancy resolvers that a payment is blocked and needs
 * resolution (Story 6.6), through the shared {@link EmailSender} seam (4.7) —
 * the <strong>fourth</strong> consumer after invites, PO send, and approvals.
 * Stub-grade (console until Notifications lands), same posture as {@code
 * ApproverNotifier}: it addresses the claimable queue — every active {@code
 * PURCHASING}/{@code ADMIN} user — since no one is assigned until someone claims.
 */
@Service
class DiscrepancyNotifier {

    private static final Logger log = LoggerFactory.getLogger(DiscrepancyNotifier.class);

    private final UserDirectoryService userDirectoryService;
    private final EmailSender emailSender;

    DiscrepancyNotifier(UserDirectoryService userDirectoryService, EmailSender emailSender) {
        this.userDirectoryService = userDirectoryService;
        this.emailSender = emailSender;
    }

    void notifyOpened(String poNumber, String failureDetail) {
        List<String> recipients = userDirectoryService.resolveDiscrepancyResolverEmails();
        if (recipients.isEmpty()) {
            log.info("Discrepancy opened for PO {}, but there are no active PURCHASING/ADMIN users to notify", poNumber);
            return;
        }
        for (String recipient : recipients) {
            emailSender.send(new EmailMessage(
                recipient,
                "A payment is blocked and needs resolution",
                "The three-way match blocked the payment for " + poNumber + ". Discrepancy: " + failureDetail
                    + " Please review the side-by-side and resolve it (correct the data, agree a credit, "
                    + "accept the difference, or dispute it)."));
        }
    }
}
