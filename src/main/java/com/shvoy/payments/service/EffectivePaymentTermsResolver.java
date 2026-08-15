package com.shvoy.payments.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.shvoy.suppliers.dto.PaymentScheduleTerms;
import com.shvoy.suppliers.service.PaymentTermsService;

/**
 * The single place "which payment terms govern this PO's payments?" is
 * answered (Story 6.2) — deliberately isolated, the same discipline as the
 * tolerance boundary, the carton rule, and the variance basis.
 *
 * <p>Today it returns the supplier's single 3.3 term set. When the still-open
 * <strong>dual-term supplier</strong> model lands (a supplier holding "current"
 * and "target" terms mid-transition), <em>only this resolver changes</em> —
 * every caller keeps asking the same question.
 *
 * <p>Called during the generation-time payment creation (a synchronous
 * listener), so reading the supplier's terms here <strong>is</strong> the
 * snapshot: whatever the terms are when the order is generated are copied onto
 * the balance payment, and a later terms change never moves an in-flight PO's
 * due dates. (Snapshot-at-generation is a flagged assumption — see
 * docs/CONTRACT.md.)
 */
@Service
public class EffectivePaymentTermsResolver {

    private final PaymentTermsService paymentTermsService;

    EffectivePaymentTermsResolver(PaymentTermsService paymentTermsService) {
        this.paymentTermsService = paymentTermsService;
    }

    /** The anchor timing to snapshot for a PO's balance, or empty when the supplier has no terms configured. */
    public Optional<PaymentScheduleTerms> resolveForPurchaseOrder(UUID supplierId) {
        return paymentTermsService.getScheduleTerms(supplierId);
    }
}
