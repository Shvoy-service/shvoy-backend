package com.shvoy.purchaseorders.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.Money;
import com.shvoy.NotFoundException;
import com.shvoy.purchaseorders.domain.PurchaseOrder;
import com.shvoy.purchaseorders.domain.PurchaseOrderLine;
import com.shvoy.purchaseorders.repository.PurchaseOrderLineRepository;
import com.shvoy.purchaseorders.repository.PurchaseOrderRepository;
import com.shvoy.suppliers.domain.PaymentSplit;
import com.shvoy.suppliers.service.PaymentTermsService;

/**
 * Story 4.3: the one reusable, deterministic, side-effect-free place PO
 * totals are composed — 4.4 (draft management), 4.6 (generation), Feature 5
 * (reconciliation), and Feature 7 (payment scheduling) must all read
 * totals from here rather than each re-summing lines independently.
 * {@link #recompute} is pure given the lines/payment terms at the time
 * it's called; persisting its result is a cache of that computation, never
 * hand-edited — callers invalidate it by calling this again, not by
 * writing to {@code PurchaseOrder}'s total fields directly.
 */
@Service
public class PurchaseOrderTotalsService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final PaymentTermsService paymentTermsService;

    PurchaseOrderTotalsService(PurchaseOrderRepository purchaseOrderRepository,
            PurchaseOrderLineRepository purchaseOrderLineRepository,
            PaymentTermsService paymentTermsService) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderLineRepository = purchaseOrderLineRepository;
        this.paymentTermsService = paymentTermsService;
    }

    /**
     * Recomputes and persists this PO's order total and deposit/balance
     * split from its current lines — call this after any line is added,
     * removed, or re-priced, so the stored totals never go stale relative
     * to the lines. Unpriced lines (see {@code PurchaseOrderLine#getPriceFound})
     * don't contribute — a line with no valid price has no total to add.
     */
    @Transactional
    public PurchaseOrder recompute(UUID purchaseOrderId) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(purchaseOrderId)
            .orElseThrow(() -> new NotFoundException("Purchase order not found"));

        Money orderTotal = sumLineTotals(purchaseOrderId);
        purchaseOrder.applyOrderTotal(orderTotal);

        Optional<PaymentSplit> split = orderTotal == null
            ? Optional.empty()
            : paymentTermsService.trySplit(purchaseOrder.getSupplierId(), orderTotal);
        if (split.isPresent()) {
            purchaseOrder.applyDepositBalanceSplit(split.get().deposit(), split.get().balance());
        } else {
            purchaseOrder.clearDepositBalanceSplit();
        }

        return purchaseOrderRepository.save(purchaseOrder);
    }

    /**
     * The order-total composition rule (docs/CONTRACT.md's Money section):
     * the sum of the lines' already-rounded 2dp totals, never a rounded
     * sum of unrounded line values — {@code Money#plus} sums two
     * already-rounded amounts exactly, so summing one line total into
     * another here introduces no further rounding of its own. Null when
     * there are no priced lines to sum, never a fabricated zero.
     */
    private Money sumLineTotals(UUID purchaseOrderId) {
        List<Money> lineTotals = purchaseOrderLineRepository.findAll().stream()
            .filter(line -> line.getPurchaseOrderId().equals(purchaseOrderId))
            .map(PurchaseOrderLine::getLineTotal)
            .filter(Objects::nonNull)
            .toList();

        Money total = null;
        for (Money lineTotal : lineTotals) {
            total = total == null ? lineTotal : total.plus(lineTotal);
        }
        return total;
    }
}
