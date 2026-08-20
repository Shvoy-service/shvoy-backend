package com.shvoy.purchaseorders.service;

import org.springframework.stereotype.Component;

import com.shvoy.EmailContent;

/**
 * The PO email's wording (Story 9.5) — the one email addressed to an
 * <em>external</em> recipient (the supplier). Names the order and the buying
 * company; the order itself rides along as the attached PDF, so the supplier
 * never needs a login to receive it. {@code PurchaseOrderSendService} owns the
 * recipient (the supplier contact) and the attachment.
 */
@Component
class PurchaseOrderEmailComposer {

    /**
     * @param poNumber         the PO reference (also the subject line's identifier)
     * @param buyerCompanyName the company the order is from — what the supplier needs to see
     * @param supplierName     the supplier being addressed
     */
    EmailContent compose(String poNumber, String buyerCompanyName, String supplierName) {
        String body = "Dear " + supplierName + ",\n\n"
            + "Please find attached purchase order " + poNumber + " from " + buyerCompanyName + ". "
            + "The full order details are in the attached PDF.\n\n"
            + "— " + buyerCompanyName + ", via SHVOY";

        return new EmailContent("Purchase Order " + poNumber, body);
    }
}
