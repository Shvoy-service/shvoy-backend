package com.shvoy.payments.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.Money;
import com.shvoy.payments.domain.PaymentStatus;
import com.shvoy.payments.dto.RunningPositionResponse;
import com.shvoy.payments.repository.GrnProjectionLineRepository;
import com.shvoy.payments.repository.InvoiceRepository;
import com.shvoy.payments.repository.PaymentRepository;
import com.shvoy.purchaseorders.dto.PurchaseOrderReconciliationView;
import com.shvoy.purchaseorders.service.PurchaseOrderService;

/**
 * The PO's running position (invoice remodel) — <strong>one</strong> derived,
 * shared, read-time-computed operation with three consumers (the 6.5 match
 * re-spec, the Finance view, statement reconciliation). Built as a single place
 * on purpose: computing % invoiced / % paid / % received in three different
 * callers would guarantee they drift apart. Nothing here is cached — the moment
 * a supersession or GRN amendment lands, a stored percentage is wrong, so every
 * number is recomputed from live state on each read.
 *
 * <p>The bases (confirmed): PO value = Σ line qty × snapshot price;
 * invoiced = Σ <em>active</em> invoice amounts; paid = Σ {@code PAID} payment
 * amounts; received = Σ GRN-projected qty × the PO's snapshot price for that SKU
 * (received value is quantity × PO price, never the invoice's price). Currency
 * differences on individual invoices are summed faithfully into the PO currency
 * rather than rejected — the running position records, it doesn't judge.
 *
 * <p>Divergence between the three percentages is normal and never validated.
 * The single boundary surfaced is {@code overInvoiced} (cumulative invoiced
 * &gt; PO value): warn-and-surface, not a block.
 */
@Service
public class RunningPositionService {

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final GrnProjectionLineRepository grnProjectionLineRepository;
    private final PurchaseOrderService purchaseOrderService;

    RunningPositionService(InvoiceRepository invoiceRepository, PaymentRepository paymentRepository,
            GrnProjectionLineRepository grnProjectionLineRepository, PurchaseOrderService purchaseOrderService) {
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.grnProjectionLineRepository = grnProjectionLineRepository;
        this.purchaseOrderService = purchaseOrderService;
    }

    @Transactional(readOnly = true)
    public RunningPositionResponse compute(UUID purchaseOrderId) {
        PurchaseOrderReconciliationView view = purchaseOrderService.getReconciliationView(purchaseOrderId);
        String currency = view.currency();

        Map<UUID, BigDecimal> priceBySku = new HashMap<>();
        BigDecimal poValue = BigDecimal.ZERO;
        for (var line : view.lines()) {
            if (line.unitPriceAmount() != null) {
                priceBySku.put(line.skuId(), line.unitPriceAmount());
                poValue = poValue.add(line.unitPriceAmount().multiply(BigDecimal.valueOf(line.quantity())));
            }
        }

        BigDecimal invoiced = invoiceRepository.findAll().stream()
            .filter(i -> i.getPurchaseOrderId().equals(purchaseOrderId) && i.isActive())
            .map(i -> i.getAmount().amount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal paid = paymentRepository.findAll().stream()
            .filter(p -> p.getPurchaseOrderId().equals(purchaseOrderId) && p.getStatus() == PaymentStatus.PAID)
            .map(p -> p.getAmount().amount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal received = grnProjectionLineRepository.findAll().stream()
            .filter(l -> l.getPurchaseOrderId().equals(purchaseOrderId) && priceBySku.containsKey(l.getSkuId()))
            .map(l -> priceBySku.get(l.getSkuId()).multiply(BigDecimal.valueOf(l.getReceivedQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean hasValue = currency != null && poValue.signum() > 0;
        return new RunningPositionResponse(
            purchaseOrderId,
            currency == null ? null : money(currency, poValue),
            currency == null ? null : money(currency, invoiced),
            currency == null ? null : money(currency, paid),
            currency == null ? null : money(currency, received),
            hasValue ? pct(invoiced, poValue) : null,
            hasValue ? pct(paid, poValue) : null,
            hasValue ? pct(received, poValue) : null,
            hasValue && invoiced.compareTo(poValue) > 0);
    }

    private static Money money(String currency, BigDecimal amount) {
        return new Money(amount.setScale(2, RoundingMode.HALF_EVEN), currency);
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 2, RoundingMode.HALF_EVEN);
    }
}
