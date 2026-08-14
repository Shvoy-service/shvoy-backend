package com.shvoy.reconciliation.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.shvoy.EmailMessage;
import com.shvoy.EmailSender;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.onboarding.service.ApproverPoolService;
import com.shvoy.reconciliation.domain.ProformaInvoice;
import com.shvoy.reconciliation.repository.ProformaInvoiceRepository;

/**
 * Notifies the approver pool that a routed PI awaits sign-off (Story 5.5),
 * through the shared {@link EmailSender} seam built in 4.7 — the third
 * consumer (after invites and PO send), which is a good sign that seam was
 * pitched at the right level: the day a real SES implementation replaces
 * {@code ConsoleEmailSender}, all three light up at once.
 *
 * <p>Stub-grade by design (console-logged until the Notifications feature
 * lands): it notifies the currently-eligible <em>pool</em> approvers. On the
 * single-approver (non-increase) path a role-only approver who isn't in the
 * pool could also act but isn't separately emailed here — an acceptable
 * simplification for the stub, noted for the real Notifications work.
 */
@Service
public class ApproverNotifier {

    private static final Logger log = LoggerFactory.getLogger(ApproverNotifier.class);

    private final ProformaInvoiceRepository proformaInvoiceRepository;
    private final ApproverPoolService approverPoolService;
    private final EmailSender emailSender;

    ApproverNotifier(ProformaInvoiceRepository proformaInvoiceRepository,
            ApproverPoolService approverPoolService, EmailSender emailSender) {
        this.proformaInvoiceRepository = proformaInvoiceRepository;
        this.approverPoolService = approverPoolService;
        this.emailSender = emailSender;
    }

    public void notifyRouted(UUID proformaInvoiceId) {
        ProformaInvoice pi = proformaInvoiceRepository.findById(proformaInvoiceId)
            .orElseThrow(() -> new NotFoundException("Proforma invoice not found"));
        TenantGuard.assertOwned(pi);

        List<String> recipients = approverPoolService.resolveEligibleApproverEmails();
        if (recipients.isEmpty()) {
            log.info("PI {} ({}) routed for approval, but the approver pool has no eligible members to notify",
                proformaInvoiceId, pi.getPiReference());
            return;
        }
        for (String recipient : recipients) {
            emailSender.send(new EmailMessage(
                recipient,
                "Proforma invoice awaiting your approval",
                "Proforma invoice " + pi.getPiReference() + " has been routed for approval. "
                    + "Please review the reconciliation and approve or reject it."));
        }
    }
}
