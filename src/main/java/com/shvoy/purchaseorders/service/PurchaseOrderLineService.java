package com.shvoy.purchaseorders.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.purchaseorders.domain.PurchaseOrder;
import com.shvoy.purchaseorders.domain.PurchaseOrderLine;
import com.shvoy.purchaseorders.dto.PurchaseOrderLineRequest;
import com.shvoy.purchaseorders.dto.PurchaseOrderResponse;
import com.shvoy.purchaseorders.repository.PurchaseOrderLineRepository;

/**
 * Story 4.4's line half: add/edit/remove, each invoking 4.2's {@link
 * PurchaseOrderLinePricingService} (which itself triggers 4.3's {@link
 * PurchaseOrderTotalsService}) rather than reimplementing pricing/totals —
 * see that story's boundary note. Ownership and the DRAFT-only status guard
 * are {@link PurchaseOrderService}'s methods, reused here rather than
 * duplicated, so a PO fetched through either service is checked identically.
 */
@Service
public class PurchaseOrderLineService {

    private final PurchaseOrderService purchaseOrderService;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final PurchaseOrderLinePricingService purchaseOrderLinePricingService;
    private final PurchaseOrderTotalsService purchaseOrderTotalsService;

    PurchaseOrderLineService(PurchaseOrderService purchaseOrderService,
            PurchaseOrderLineRepository purchaseOrderLineRepository,
            PurchaseOrderLinePricingService purchaseOrderLinePricingService,
            PurchaseOrderTotalsService purchaseOrderTotalsService) {
        this.purchaseOrderService = purchaseOrderService;
        this.purchaseOrderLineRepository = purchaseOrderLineRepository;
        this.purchaseOrderLinePricingService = purchaseOrderLinePricingService;
        this.purchaseOrderTotalsService = purchaseOrderTotalsService;
    }

    @Transactional
    public PurchaseOrderResponse addLine(UUID purchaseOrderId, PurchaseOrderLineRequest request) {
        PurchaseOrder purchaseOrder = purchaseOrderService.findOwnPurchaseOrder(purchaseOrderId);
        PurchaseOrderService.assertEditable(purchaseOrder);

        int lineNumber = nextLineNumber(purchaseOrderId);
        PurchaseOrderLine line = purchaseOrderLineRepository.save(
            new PurchaseOrderLine(purchaseOrderId, request.skuId(), lineNumber, request.quantity()));
        // priceLine (4.2) recomputes totals (4.3) itself — see its own Javadoc.
        purchaseOrderLinePricingService.priceLine(line);

        return purchaseOrderService.toResponse(purchaseOrderService.findOwnPurchaseOrder(purchaseOrderId));
    }

    @Transactional
    public PurchaseOrderResponse updateLine(UUID purchaseOrderId, UUID lineId, PurchaseOrderLineRequest request) {
        PurchaseOrder purchaseOrder = purchaseOrderService.findOwnPurchaseOrder(purchaseOrderId);
        PurchaseOrderService.assertEditable(purchaseOrder);

        PurchaseOrderLine line = findOwnLine(purchaseOrderId, lineId);
        line.update(request.skuId(), request.quantity());
        purchaseOrderLineRepository.save(line);
        // Re-price: the sku/quantity changed, so the price snapshot must not go stale — same as a brand-new line.
        purchaseOrderLinePricingService.priceLine(line);

        return purchaseOrderService.toResponse(purchaseOrderService.findOwnPurchaseOrder(purchaseOrderId));
    }

    @Transactional
    public PurchaseOrderResponse removeLine(UUID purchaseOrderId, UUID lineId) {
        PurchaseOrder purchaseOrder = purchaseOrderService.findOwnPurchaseOrder(purchaseOrderId);
        PurchaseOrderService.assertEditable(purchaseOrder);

        PurchaseOrderLine line = findOwnLine(purchaseOrderId, lineId);
        purchaseOrderLineRepository.delete(line);
        // No pricing step to trigger totals this time — recompute directly, same as PurchaseOrderLinePricingService does after a (re)price.
        purchaseOrderTotalsService.recompute(purchaseOrderId);

        return purchaseOrderService.toResponse(purchaseOrderService.findOwnPurchaseOrder(purchaseOrderId));
    }

    private int nextLineNumber(UUID purchaseOrderId) {
        return purchaseOrderLineRepository.findAll().stream()
            .filter(line -> line.getPurchaseOrderId().equals(purchaseOrderId))
            .mapToInt(PurchaseOrderLine::getLineNumber)
            .max()
            .orElse(0) + 1;
    }

    private PurchaseOrderLine findOwnLine(UUID purchaseOrderId, UUID lineId) {
        PurchaseOrderLine line = purchaseOrderLineRepository.findById(lineId)
            .orElseThrow(() -> new NotFoundException("Purchase order line not found"));
        TenantGuard.assertOwned(line);
        if (!line.getPurchaseOrderId().equals(purchaseOrderId)) {
            throw new NotFoundException("Purchase order line not found");
        }
        return line;
    }
}
