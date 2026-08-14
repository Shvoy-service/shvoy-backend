package com.shvoy.purchaseorders.service;

import java.time.LocalDate;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.ErrorCode;
import com.shvoy.NotFoundException;
import com.shvoy.ValidationException;
import com.shvoy.purchaseorders.domain.PurchaseOrder;
import com.shvoy.purchaseorders.domain.PurchaseOrderLine;
import com.shvoy.purchaseorders.repository.PurchaseOrderLineRepository;
import com.shvoy.purchaseorders.repository.PurchaseOrderRepository;
import com.shvoy.suppliers.dto.PriceResolutionResult;
import com.shvoy.suppliers.service.PriceResolutionService;

/**
 * Story 4.2: wires a {@link PurchaseOrderLine} to 3.8's {@link
 * PriceResolutionService} and snapshots the result — see
 * {@code PurchaseOrderLine#applyPriceResolution}. This is the logic 4.4's
 * create/edit endpoints call, not an endpoint itself (none exist yet).
 *
 * Resolves as of the current date (the draft date) — a PO being created
 * now wants the price valid today. 4.6 (generation) re-resolves and
 * re-snapshots at that later point; this story only prices the working
 * draft, it doesn't hold generation-time semantics.
 *
 * Also triggers {@link PurchaseOrderTotalsService#recompute} after every
 * (re)price (Story 4.3) — a line's total changing is exactly the kind of
 * line mutation the PO's stored order total/deposit/balance must never go
 * stale relative to.
 */
@Service
public class PurchaseOrderLinePricingService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final PriceResolutionService priceResolutionService;
    private final PurchaseOrderTotalsService purchaseOrderTotalsService;

    PurchaseOrderLinePricingService(PurchaseOrderRepository purchaseOrderRepository,
            PurchaseOrderLineRepository purchaseOrderLineRepository,
            PriceResolutionService priceResolutionService,
            PurchaseOrderTotalsService purchaseOrderTotalsService) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderLineRepository = purchaseOrderLineRepository;
        this.priceResolutionService = priceResolutionService;
        this.purchaseOrderTotalsService = purchaseOrderTotalsService;
    }

    /**
     * {@code line} must already be persisted (added via 4.4, not built
     * here) — its {@code purchaseOrderId} is looked up to find the PO's
     * supplier, since 3.8 resolves per-supplier and a line's own record
     * shouldn't need a caller to separately supply what its PO already
     * says. Cross-tenant/nonexistent PO, or a SKU that doesn't belong to
     * that supplier, both surface as {@code NotFoundException} — the
     * latter for free, via {@link PriceResolutionService}'s own ownership
     * chain, not reimplemented here.
     */
    @Transactional
    public PurchaseOrderLine priceLine(PurchaseOrderLine line) {
        if (line.getQuantity() <= 0) {
            throw new ValidationException("quantity must be positive");
        }

        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(line.getPurchaseOrderId())
            .orElseThrow(() -> new NotFoundException("Purchase order not found"));

        PriceResolutionResult result = priceResolutionService.resolve(
            purchaseOrder.getSupplierId(), line.getSkuId(), line.getQuantity(), LocalDate.now());

        if (result.priceFound()) {
            assertCurrencyConsistentWithOtherLines(line, result.unitPrice().currency());
        }

        line.applyPriceResolution(result);
        PurchaseOrderLine saved = purchaseOrderLineRepository.save(line);
        // 4.3: a re-priced line's total has changed, so the PO's stored
        // order total/deposit/balance must never go stale relative to it.
        purchaseOrderTotalsService.recompute(saved.getPurchaseOrderId());
        return saved;
    }

    /**
     * A single PO to a single supplier in one currency — sidesteps the
     * harder multi-currency question (still open with the Product Owners,
     * see Money in docs/CONTRACT.md) at PO-creation time, leaving it where
     * it belongs: Feature 5 reconciliation. Flagged as a real decision
     * this story introduces, pending confirmation it fits how SHVOY's
     * suppliers actually quote.
     */
    private void assertCurrencyConsistentWithOtherLines(PurchaseOrderLine line, String currency) {
        boolean mismatched = purchaseOrderLineRepository.findAll().stream()
            .filter(other -> other.getPurchaseOrderId().equals(line.getPurchaseOrderId()))
            .filter(other -> !Objects.equals(other.getId(), line.getId()))
            .map(PurchaseOrderLine::getUnitPrice)
            .filter(Objects::nonNull)
            .anyMatch(existingPrice -> !existingPrice.currency().equals(currency));
        if (mismatched) {
            throw new ConflictException(ErrorCode.CURRENCY_MISMATCH,
                "This line resolved to " + currency + ", which doesn't match this PO's existing lines");
        }
    }
}
