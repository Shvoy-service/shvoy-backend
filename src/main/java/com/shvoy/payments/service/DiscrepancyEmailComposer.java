package com.shvoy.payments.service;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.shvoy.EmailContent;
import com.shvoy.FrontendLinks;

/**
 * The discrepancy email's wording (Story 9.5) — the blocked payment, which
 * order, a short summary of what mismatched (not the full side-by-side), and the
 * link to the case. Enough to triage; the app holds the record. {@code
 * DiscrepancyNotifier} owns who receives it.
 */
@Component
class DiscrepancyEmailComposer {

    private final FrontendLinks frontendLinks;

    DiscrepancyEmailComposer(FrontendLinks frontendLinks) {
        this.frontendLinks = frontendLinks;
    }

    /**
     * @param poNumber      the PO whose payment is blocked
     * @param failureDetail a short summary of which leg(s) mismatched
     * @param caseId        keys the discrepancy-case-detail link
     */
    EmailContent compose(String poNumber, String failureDetail, UUID caseId) {
        String body = "The three-way match blocked the payment for purchase order " + poNumber + ".\n\n"
            + "What mismatched: " + failureDetail + "\n\n"
            + "Review the case and resolve it — correct the data, agree a credit, accept the difference, "
            + "or dispute it:\n"
            + frontendLinks.discrepancyCase(caseId) + "\n\n"
            + "— SHVOY";

        return new EmailContent("Payment discrepancy — " + poNumber, body);
    }
}
