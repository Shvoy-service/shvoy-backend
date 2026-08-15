package com.shvoy.payments.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.shvoy.payments.domain.Invoice;
import com.shvoy.payments.domain.InvoiceCoversType;
import com.shvoy.payments.repository.InvoiceRepository;

/**
 * <strong>The invoice-anchor publishing policy — one isolated decision point
 * (invoice remodel; flagged interpretation).</strong> Under one-active-invoice
 * cardinality the INVOICE anchor date came from "the" invoice. With many
 * concurrent invoices per PO, that's ambiguous, so the confirmed policy is: the
 * anchor publishes from the <em>first non-deposit invoice</em> — a deposit
 * invoice never anchors a balance due date, and later independent invoices
 * (e.g. a second shipment) don't move an anchor the first one already set.
 *
 * <p>A correction to that first non-deposit invoice's chain <em>does</em>
 * re-fire (its date may have changed): "publish" holds when the invoice just
 * recorded is, or supersedes, the earliest-created non-deposit invoice for the
 * PO. This is the single knob that changes if the PO owner reads the anchoring
 * rule differently — deliberately not smeared across {@code InvoiceService}.
 */
@Component
class InvoiceAnchorPolicy {

    private final InvoiceRepository invoiceRepository;

    InvoiceAnchorPolicy(InvoiceRepository invoiceRepository) {
        this.invoiceRepository = invoiceRepository;
    }

    /** Whether recording {@code invoiceId} should (re-)publish the PO's INVOICE anchor date. */
    boolean shouldPublishAnchor(UUID purchaseOrderId, UUID invoiceId) {
        List<Invoice> forPo = invoiceRepository.findAll().stream()
            .filter(i -> i.getPurchaseOrderId().equals(purchaseOrderId))
            .toList();

        Optional<Invoice> earliestNonDeposit = forPo.stream()
            .filter(i -> i.getCoversType() != InvoiceCoversType.DEPOSIT)
            .min(Comparator.comparing(Invoice::getCreatedAt));
        if (earliestNonDeposit.isEmpty()) {
            return false;
        }

        Map<UUID, Invoice> byId = new HashMap<>();
        forPo.forEach(i -> byId.put(i.getId(), i));

        Set<UUID> chain = new HashSet<>();
        UUID cursor = invoiceId;
        while (cursor != null && chain.add(cursor)) {
            Invoice node = byId.get(cursor);
            cursor = node == null ? null : node.getSupersedesInvoiceId();
        }
        return chain.contains(earliestNonDeposit.get().getId());
    }
}
