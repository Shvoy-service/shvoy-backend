package com.shvoy.shipments.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.Money;
import com.shvoy.UnitPrice;
import com.shvoy.shipments.domain.GoodsReceiptLine;
import com.shvoy.shipments.domain.ShipmentConsignment;
import com.shvoy.shipments.dto.ReceiptRollupLine;
import com.shvoy.shipments.dto.ReceiptRollupResponse;
import com.shvoy.shipments.repository.GoodsReceiptLineRepository;
import com.shvoy.shipments.repository.ShipmentConsignmentRepository;
import com.shvoy.purchaseorders.dto.PurchaseOrderReconciliationLine;
import com.shvoy.purchaseorders.dto.PurchaseOrderReconciliationView;
import com.shvoy.purchaseorders.service.PurchaseOrderService;

/**
 * Receipt rollup &amp; PO closure — the <strong>one owner</strong> of the
 * cumulative-receipt operation. Given a PO, it sums the per-SKU received
 * quantities across <em>all</em> its consignments' GRNs (7.4 snapshots) up to the
 * parent PO, values them at PO snapshot prices (the basis pinned by 6.4), and
 * decides the closure condition. Lives with the receipt data in {@code shipments}.
 *
 * <p><strong>Derived at read time, never stored counters</strong> — the drift
 * rule: a GRN amendment must reflect instantly. Same shape as 6.4's running
 * position. (The payments side keeps its own GRN projection for 6.5 / the running
 * position because {@code payments} can't depend on {@code shipments} without a
 * cycle — that projection is the sanctioned cross-boundary copy of the same GRN
 * snapshots; this service is the canonical owner on the receipt side.)
 *
 * <p><strong>Closure is per-SKU exact.</strong> Complete means every ordered line
 * met exactly — a shortfall on SKU A netting against an overage on SKU B is
 * <em>not</em> complete, it's a double discrepancy. The receipt side observes the
 * fact and tells {@code purchaseorders} to close/reopen; it never commands a
 * status the PO doesn't own.
 */
@Service
public class ReceiptRollupService {

    private final ShipmentConsignmentRepository consignmentRepository;
    private final GoodsReceiptLineRepository goodsReceiptLineRepository;
    private final PurchaseOrderService purchaseOrderService;

    ReceiptRollupService(ShipmentConsignmentRepository consignmentRepository,
            GoodsReceiptLineRepository goodsReceiptLineRepository, PurchaseOrderService purchaseOrderService) {
        this.consignmentRepository = consignmentRepository;
        this.goodsReceiptLineRepository = goodsReceiptLineRepository;
        this.purchaseOrderService = purchaseOrderService;
    }

    /** The read-time rollup view — per-SKU ordered vs received, valued, plus the closure/over-delivery facts. */
    @Transactional(readOnly = true)
    public ReceiptRollupResponse getRollup(UUID purchaseOrderId) {
        return assess(purchaseOrderId).toResponse();
    }

    /**
     * Re-evaluate closure after a GRN was created/amended and push the observed
     * fact to {@code purchaseorders}. Called synchronously from the GRN flow — the
     * receipt is already committed, so a closure evaluation reflects durable data.
     */
    @Transactional
    public void reassessClosure(UUID purchaseOrderId) {
        Assessment a = assess(purchaseOrderId);
        purchaseOrderService.applyReceiptClosure(purchaseOrderId, a.complete(), a.overDelivered());
    }

    /** The Finance/Admin close-short action — compute the outstanding remainder, then write off via the PO lifecycle. */
    @Transactional
    public void closeShort(UUID purchaseOrderId, String reason) {
        Assessment a = assess(purchaseOrderId);
        purchaseOrderService.closeShort(purchaseOrderId, reason, a.outstandingSummary());
    }

    private Assessment assess(UUID purchaseOrderId) {
        Set<UUID> consignmentIds = consignmentRepository.findAll().stream()
            .filter(c -> c.getPurchaseOrderId().equals(purchaseOrderId) && !c.isDetached())
            .map(ShipmentConsignment::getId)
            .collect(java.util.stream.Collectors.toSet());

        Map<UUID, Integer> received = new HashMap<>();
        for (GoodsReceiptLine line : goodsReceiptLineRepository.findAll()) {
            if (consignmentIds.contains(line.getConsignmentId())) {
                received.merge(line.getSkuId(), line.getReceivedQuantity(), Integer::sum);
            }
        }

        PurchaseOrderReconciliationView view = purchaseOrderService.getReconciliationView(purchaseOrderId);
        String currency = view.currency();
        Map<UUID, Integer> ordered = new HashMap<>();
        Map<UUID, BigDecimal> prices = new HashMap<>();
        for (PurchaseOrderReconciliationLine line : view.lines()) {
            ordered.merge(line.skuId(), line.quantity(), Integer::sum);
            if (line.unitPriceAmount() != null) {
                prices.put(line.skuId(), line.unitPriceAmount());
            }
        }

        Set<UUID> skus = new LinkedHashSet<>(ordered.keySet());
        skus.addAll(received.keySet());

        boolean overDelivered = false;
        boolean short_ = false;
        List<ReceiptRollupLine> lines = new java.util.ArrayList<>();
        BigDecimal receivedValue = BigDecimal.ZERO;
        BigDecimal orderedValue = BigDecimal.ZERO;
        BigDecimal outstandingValue = BigDecimal.ZERO;
        for (UUID sku : skus) {
            int ord = ordered.getOrDefault(sku, 0);
            int rec = received.getOrDefault(sku, 0);
            boolean over = rec > ord;
            overDelivered |= over;
            short_ |= rec < ord;
            BigDecimal price = prices.get(sku);
            Money lineValue = null;
            if (price != null && currency != null) {
                lineValue = new UnitPrice(price, currency).multiply(rec);
                receivedValue = receivedValue.add(price.multiply(BigDecimal.valueOf(rec)));
                orderedValue = orderedValue.add(price.multiply(BigDecimal.valueOf(ord)));
                outstandingValue = outstandingValue.add(price.multiply(BigDecimal.valueOf(Math.max(0, ord - rec))));
            }
            lines.add(new ReceiptRollupLine(sku, ord, rec, over, lineValue));
        }

        // Complete = every ordered line met exactly, none over, and there IS something ordered.
        boolean complete = !ordered.isEmpty() && !overDelivered && !short_;
        return new Assessment(purchaseOrderId, currency, complete, overDelivered, lines,
            money(currency, orderedValue), money(currency, receivedValue), money(currency, outstandingValue));
    }

    private static Money money(String currency, BigDecimal amount) {
        return currency == null ? null : new Money(amount.setScale(2, java.math.RoundingMode.HALF_EVEN), currency);
    }

    private record Assessment(UUID purchaseOrderId, String currency, boolean complete, boolean overDelivered,
            List<ReceiptRollupLine> lines, Money orderedValue, Money receivedValue, Money outstandingValue) {

        ReceiptRollupResponse toResponse() {
            return new ReceiptRollupResponse(purchaseOrderId, currency, complete, overDelivered,
                orderedValue, receivedValue, outstandingValue, lines);
        }

        String outstandingSummary() {
            String value = outstandingValue == null ? "unpriced"
                : outstandingValue.currency() + " " + outstandingValue.amount().toPlainString();
            String skus = lines.stream()
                .filter(l -> l.receivedQuantity() < l.orderedQuantity())
                .map(l -> l.skuId() + " (" + (l.orderedQuantity() - l.receivedQuantity()) + " short)")
                .collect(java.util.stream.Collectors.joining(", "));
            return value + (skus.isEmpty() ? "" : " — outstanding lines: " + skus);
        }
    }
}
