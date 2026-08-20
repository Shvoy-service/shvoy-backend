package com.shvoy.reconciliation.service;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.shvoy.EmailContent;
import com.shvoy.FrontendLinks;

/**
 * The approval-request email's wording (Story 9.5) — enough to triage from the
 * inbox without logging in blind: which order, the supplier, whether it's a
 * price-increase (2-of-N) case, and the link to the reconciliation screen.
 * {@code ApproverNotifier} owns who receives it.
 */
@Component
class ApprovalRequestEmailComposer {

    private final FrontendLinks frontendLinks;

    ApprovalRequestEmailComposer(FrontendLinks frontendLinks) {
        this.frontendLinks = frontendLinks;
    }

    /**
     * @param poNumber              the PO the routed PI belongs to
     * @param supplierName          the supplier on that PO
     * @param priceIncreaseSignOff  true if the 2-of-N price-increase gate applies (needs N distinct sign-offs)
     * @param requiredSignOffCount  the number of distinct sign-offs required when the gate applies
     * @param proformaInvoiceId     keys the reconciliation-screen link
     */
    EmailContent compose(String poNumber, String supplierName, boolean priceIncreaseSignOff,
            int requiredSignOffCount, UUID proformaInvoiceId) {
        String nature = priceIncreaseSignOff
            ? "It contains a unit-price increase beyond tolerance, so it needs " + requiredSignOffCount
                + " distinct approver sign-offs before it can proceed."
            : "It was routed on a variance outside tolerance and can be confirmed by a single approver.";

        String body = "Purchase order " + poNumber + " (" + supplierName + ") has a proforma invoice "
            + "awaiting your approval.\n\n"
            + nature + "\n\n"
            + "Review the reconciliation and approve or reject it:\n"
            + frontendLinks.reconciliation(proformaInvoiceId) + "\n\n"
            + "— SHVOY";

        return new EmailContent("Approval needed — PI variance on " + poNumber, body);
    }
}
