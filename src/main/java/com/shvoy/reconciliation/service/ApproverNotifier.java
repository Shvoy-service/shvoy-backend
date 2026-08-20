package com.shvoy.reconciliation.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.shvoy.EmailContent;
import com.shvoy.EmailMessage;
import com.shvoy.EmailSource;
import com.shvoy.EmailSender;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.onboarding.service.ApproverPoolService;
import com.shvoy.onboarding.service.UserDirectoryService;
import com.shvoy.purchaseorders.dto.PurchaseOrderSummary;
import com.shvoy.purchaseorders.service.PurchaseOrderService;
import com.shvoy.reconciliation.domain.ProformaInvoice;
import com.shvoy.reconciliation.repository.ProformaInvoiceRepository;
import com.shvoy.suppliers.dto.SupplierSummary;
import com.shvoy.suppliers.service.SupplierService;

/**
 * Notifies the right approvers that a routed PI awaits sign-off (Story 5.5),
 * through the shared {@link EmailSender} seam built in 4.7.
 *
 * <p>Recipients branch on the same rule that governs the gate itself (defined
 * once in {@link PiApprovalService#requiresPriceIncreaseSignOff}): a PI with a
 * unit-price increase beyond tolerance needs 2-of-N sign-off, so it goes to the
 * currently-<em>eligible pool</em>; any other routed PI can be confirmed by a
 * single approver, so it goes to <em>all active {@code APPROVER}-role holders</em>.
 * The content (which order, supplier, whether it's a price increase, the link)
 * is the composer's; this class owns only the recipient decision.
 */
@Service
public class ApproverNotifier {

    private static final Logger log = LoggerFactory.getLogger(ApproverNotifier.class);

    private final ProformaInvoiceRepository proformaInvoiceRepository;
    private final ApproverPoolService approverPoolService;
    private final UserDirectoryService userDirectoryService;
    private final PiApprovalService piApprovalService;
    private final PurchaseOrderService purchaseOrderService;
    private final SupplierService supplierService;
    private final ApprovalRequestEmailComposer approvalRequestEmailComposer;
    private final EmailSender emailSender;

    ApproverNotifier(ProformaInvoiceRepository proformaInvoiceRepository,
            ApproverPoolService approverPoolService, UserDirectoryService userDirectoryService,
            PiApprovalService piApprovalService, PurchaseOrderService purchaseOrderService,
            SupplierService supplierService, ApprovalRequestEmailComposer approvalRequestEmailComposer,
            EmailSender emailSender) {
        this.proformaInvoiceRepository = proformaInvoiceRepository;
        this.approverPoolService = approverPoolService;
        this.userDirectoryService = userDirectoryService;
        this.piApprovalService = piApprovalService;
        this.purchaseOrderService = purchaseOrderService;
        this.supplierService = supplierService;
        this.approvalRequestEmailComposer = approvalRequestEmailComposer;
        this.emailSender = emailSender;
    }

    public void notifyRouted(UUID proformaInvoiceId) {
        ProformaInvoice pi = proformaInvoiceRepository.findById(proformaInvoiceId)
            .orElseThrow(() -> new NotFoundException("Proforma invoice not found"));
        TenantGuard.assertOwned(pi);

        boolean priceIncreaseSignOff = piApprovalService.requiresPriceIncreaseSignOff(proformaInvoiceId);
        List<String> recipients = priceIncreaseSignOff
            ? approverPoolService.resolveEligibleApproverEmails()
            : userDirectoryService.resolveApproverRoleEmails();
        if (recipients.isEmpty()) {
            log.info("PI {} ({}) routed for approval, but there are no {} to notify",
                proformaInvoiceId, pi.getPiReference(),
                priceIncreaseSignOff ? "eligible approver-pool members" : "active APPROVER-role users");
            return;
        }

        PurchaseOrderSummary po = purchaseOrderService.getSummary(pi.getPurchaseOrderId());
        SupplierSummary supplier = supplierService.getSummary(po.supplierId());
        EmailContent content = approvalRequestEmailComposer.compose(
            po.poNumber(), supplier.name(), priceIncreaseSignOff,
            approverPoolService.requiredSignOffCount(), proformaInvoiceId);

        for (String recipient : recipients) {
            emailSender.send(new EmailMessage(
                recipient, content.subject(), content.body(),
                EmailSource.APPROVAL_REQUEST, pi.getPiReference()));
        }
    }
}
