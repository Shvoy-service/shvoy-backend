package com.shvoy.reconciliation.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.UnitPrice;
import com.shvoy.ValidationException;
import com.shvoy.purchaseorders.dto.PurchaseOrderReconciliationLine;
import com.shvoy.purchaseorders.dto.PurchaseOrderReconciliationView;
import com.shvoy.purchaseorders.service.PurchaseOrderService;
import com.shvoy.reconciliation.domain.ProformaInvoice;
import com.shvoy.reconciliation.domain.ProformaInvoiceLine;
import com.shvoy.reconciliation.domain.Reconciliation;
import com.shvoy.reconciliation.domain.ReconciliationLine;
import com.shvoy.reconciliation.dto.ReconciliationLineResponse;
import com.shvoy.reconciliation.dto.ReconciliationResponse;
import com.shvoy.reconciliation.dto.VarianceDirection;
import com.shvoy.reconciliation.repository.ProformaInvoiceLineRepository;
import com.shvoy.reconciliation.repository.ProformaInvoiceRepository;
import com.shvoy.reconciliation.repository.ReconciliationLineRepository;
import com.shvoy.reconciliation.repository.ReconciliationRepository;
import com.shvoy.suppliers.dto.PriceResolutionResult;
import com.shvoy.suppliers.service.PriceResolutionService;

/**
 * Story 5.3 — the three-way comparison at the heart of reconciliation: for a
 * logged PI, gather the PO leg (Feature 4's price snapshot), the PI leg (the
 * supplier's confirmed values, 5.1/5.2), and the price-file formula leg (3.8,
 * resolved as of the PO's generation date), correlate them by SKU, compute
 * the per-line variance, and persist the whole result.
 *
 * <strong>Computes and records only.</strong> It makes no tolerance or
 * routing decision — that's 5.4, which reads the stored result this produces.
 * The computation is deterministic and side-effect-free given the PO, PI, and
 * resolved price-file values (the persistence is the only effect).
 *
 * Reached via {@link ReconciliationTriggerService} (5.2's post-log seam):
 * logging a PI triggers a comparison, but the logging transaction has already
 * committed by the time this runs and a failure here is swallowed by the
 * caller — so a reconciliation failure never loses the logged PI.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ProformaInvoiceRepository proformaInvoiceRepository;
    private final ProformaInvoiceLineRepository proformaInvoiceLineRepository;
    private final ReconciliationRepository reconciliationRepository;
    private final ReconciliationLineRepository reconciliationLineRepository;
    private final PurchaseOrderService purchaseOrderService;
    private final PriceResolutionService priceResolutionService;

    ReconciliationService(
            ProformaInvoiceRepository proformaInvoiceRepository,
            ProformaInvoiceLineRepository proformaInvoiceLineRepository,
            ReconciliationRepository reconciliationRepository,
            ReconciliationLineRepository reconciliationLineRepository,
            PurchaseOrderService purchaseOrderService,
            PriceResolutionService priceResolutionService) {
        this.proformaInvoiceRepository = proformaInvoiceRepository;
        this.proformaInvoiceLineRepository = proformaInvoiceLineRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.reconciliationLineRepository = reconciliationLineRepository;
        this.purchaseOrderService = purchaseOrderService;
        this.priceResolutionService = priceResolutionService;
    }

    @Transactional
    public UUID reconcile(UUID proformaInvoiceId) {
        ProformaInvoice proformaInvoice = findOwnProformaInvoice(proformaInvoiceId);
        PurchaseOrderReconciliationView po =
            purchaseOrderService.getReconciliationView(proformaInvoice.getPurchaseOrderId());

        String piCurrency = proformaInvoice.getCurrency();
        String poCurrency = po.currency();
        boolean currencyMismatch = poCurrency != null && !poCurrency.equals(piCurrency);

        Reconciliation reconciliation = reconciliationRepository.save(new Reconciliation(
            proformaInvoice.getId(), po.purchaseOrderId(), VarianceCalculator.BASIS,
            po.generationDate(), poCurrency, piCurrency, currencyMismatch));

        List<ProformaInvoiceLine> piLines = proformaInvoiceLineRepository.findAll().stream()
            .filter(line -> line.getProformaInvoiceId().equals(proformaInvoice.getId()))
            .toList();

        List<ReconciliationLine> findings =
            correlate(reconciliation.getId(), po, piLines, currencyMismatch);
        reconciliationLineRepository.saveAll(findings);

        return reconciliation.getId();
    }

    /**
     * SKU-based correlation (the 5.1 decision) with the messy cases surfaced,
     * not assumed away (scope item 5): a SKU appearing more than once on
     * either side is a {@code DUPLICATE_SKU} finding (pairing can't be
     * guessed); a SKU on exactly one side is an unmatched finding; a SKU on
     * both sides once is a matched line with a computed variance. Order is
     * PO-lines-first then any PI-only SKUs, for a stable, readable result.
     */
    private List<ReconciliationLine> correlate(UUID reconciliationId, PurchaseOrderReconciliationView po,
            List<ProformaInvoiceLine> piLines, boolean currencyMismatch) {
        Map<UUID, List<PurchaseOrderReconciliationLine>> poBySku = po.lines().stream()
            .collect(Collectors.groupingBy(PurchaseOrderReconciliationLine::skuId));
        Map<UUID, List<ProformaInvoiceLine>> piBySku = piLines.stream()
            .collect(Collectors.groupingBy(ProformaInvoiceLine::getSkuId));

        Set<UUID> allSkus = new LinkedHashSet<>();
        po.lines().forEach(line -> allSkus.add(line.skuId()));
        piLines.forEach(line -> allSkus.add(line.getSkuId()));

        List<ReconciliationLine> findings = new ArrayList<>();
        for (UUID skuId : allSkus) {
            List<PurchaseOrderReconciliationLine> poForSku = poBySku.getOrDefault(skuId, List.of());
            List<ProformaInvoiceLine> piForSku = piBySku.getOrDefault(skuId, List.of());

            if (poForSku.size() > 1 || piForSku.size() > 1) {
                findings.add(ReconciliationLine.ofDuplicateSku(reconciliationId, skuId));
            } else if (!poForSku.isEmpty() && !piForSku.isEmpty()) {
                findings.add(matchedLine(reconciliationId, po, poForSku.get(0), piForSku.get(0), currencyMismatch));
            } else if (!piForSku.isEmpty()) {
                ProformaInvoiceLine pi = piForSku.get(0);
                findings.add(ReconciliationLine.ofUnmatchedPiLine(
                    reconciliationId, skuId, pi.getConfirmedUnitPriceAmount(), pi.getConfirmedQuantity()));
            } else {
                PurchaseOrderReconciliationLine poLine = poForSku.get(0);
                findings.add(ReconciliationLine.ofUnmatchedPoLine(
                    reconciliationId, skuId, poLine.unitPriceAmount(), poLine.quantity()));
            }
        }
        return findings;
    }

    private ReconciliationLine matchedLine(UUID reconciliationId, PurchaseOrderReconciliationView po,
            PurchaseOrderReconciliationLine poLine, ProformaInvoiceLine piLine, boolean currencyMismatch) {
        BigDecimal poUnitPrice = poLine.unitPriceAmount();
        int poQuantity = poLine.quantity();
        BigDecimal piUnitPrice = piLine.getConfirmedUnitPriceAmount();
        int piQuantity = piLine.getConfirmedQuantity();

        PriceResolutionResult priceFile = resolvePriceFileLeg(po, poLine.skuId(), poQuantity);
        BigDecimal priceFileUnitPrice = priceFile != null && priceFile.priceFound()
            ? priceFile.unitPrice().amount() : null;
        boolean priceFileFound = priceFile != null && priceFile.priceFound();

        // A cross-currency variance is meaningless without an FX rate (Phase 2), so it's left null and
        // flagged on the header instead — but quantity is currency-independent, so it's always computed.
        BigDecimal unitPriceVariancePct = currencyMismatch
            ? null
            : VarianceCalculator.unitPriceVariancePct(poUnitPrice, poQuantity, piUnitPrice, piQuantity);
        BigDecimal quantityVariancePct = VarianceCalculator.quantityVariancePct(poQuantity, piQuantity);
        int quantityVarianceAbs = VarianceCalculator.quantityVarianceAbs(poQuantity, piQuantity);

        return ReconciliationLine.ofMatched(reconciliationId, poLine.skuId(),
            poUnitPrice, poQuantity, piUnitPrice, piQuantity,
            priceFileUnitPrice, priceFileFound,
            unitPriceVariancePct, quantityVariancePct, quantityVarianceAbs);
    }

    /**
     * The price-file leg — 3.8 resolved for the PO's supplier as of the PO
     * generation date, at the ordered (PO) quantity, so it reproduces what
     * the PO leg's price should have been (the independent "was the PO priced
     * correctly" reference). Best-effort: a resolution failure (the date is
     * unknown, or 3.8 rejects the SKU/supplier for some reason) leaves this
     * leg absent rather than aborting the whole comparison — the PI-vs-PO
     * variance doesn't depend on it.
     */
    private PriceResolutionResult resolvePriceFileLeg(PurchaseOrderReconciliationView po, UUID skuId, int poQuantity) {
        if (po.generationDate() == null) {
            return null;
        }
        try {
            return priceResolutionService.resolve(po.supplierId(), skuId, poQuantity, po.generationDate());
        } catch (NotFoundException | ValidationException e) {
            log.warn("Price-file leg unresolvable for SKU {} on PO {} as of {} — recording it as absent",
                skuId, po.purchaseOrderId(), po.generationDate(), e);
            return null;
        }
    }

    /**
     * The latest reconciliation recorded for a PI — newest first, since a PI
     * is normally reconciled once (a correction is a new PI, 5.1), but a
     * re-trigger would append rather than overwrite, and the current
     * comparison is the most recent. {@code NOT_FOUND} if the PI has none yet
     * (e.g. its comparison failed at log time — the PI is still logged).
     */
    @Transactional(readOnly = true)
    public ReconciliationResponse getForProformaInvoice(UUID proformaInvoiceId) {
        findOwnProformaInvoice(proformaInvoiceId);
        Reconciliation reconciliation = reconciliationRepository.findAll().stream()
            .filter(r -> r.getProformaInvoiceId().equals(proformaInvoiceId))
            .max(Comparator.comparing(Reconciliation::getCreatedAt))
            .orElseThrow(() -> new NotFoundException("No reconciliation recorded for this proforma invoice"));
        return toResponse(reconciliation);
    }

    private ReconciliationResponse toResponse(Reconciliation reconciliation) {
        List<ReconciliationLineResponse> lines = reconciliationLineRepository.findAll().stream()
            .filter(line -> line.getReconciliationId().equals(reconciliation.getId()))
            .sorted(Comparator.comparing(ReconciliationLine::getCreatedAt))
            .map(line -> toLineResponse(line, reconciliation))
            .toList();

        return new ReconciliationResponse(
            reconciliation.getId(),
            reconciliation.getProformaInvoiceId(),
            reconciliation.getPurchaseOrderId(),
            reconciliation.getVarianceBasis(),
            reconciliation.getPriceFileAsOfDate(),
            reconciliation.getPoCurrency(),
            reconciliation.getPiCurrency(),
            reconciliation.isCurrencyMismatch(),
            lines,
            reconciliation.getCreatedAt());
    }

    private static ReconciliationLineResponse toLineResponse(ReconciliationLine line, Reconciliation reconciliation) {
        return new ReconciliationLineResponse(
            line.getSkuId(),
            line.getFindingType(),
            unitPrice(line.getPoUnitPriceAmount(), reconciliation.getPoCurrency()),
            line.getPoQuantity(),
            unitPrice(line.getPiUnitPriceAmount(), reconciliation.getPiCurrency()),
            line.getPiQuantity(),
            unitPrice(line.getPriceFileUnitPriceAmount(), reconciliation.getPoCurrency()),
            line.getPriceFilePriceFound(),
            line.getUnitPriceVariancePct(),
            VarianceDirection.of(line.getUnitPriceVariancePct()),
            line.getQuantityVariancePct(),
            line.getQuantityVarianceAbs());
    }

    /** The price-file leg is resolved for the PO's supplier, so its currency is the PO's. */
    private static UnitPrice unitPrice(BigDecimal amount, String currency) {
        return amount == null || currency == null ? null : new UnitPrice(amount, currency);
    }

    private ProformaInvoice findOwnProformaInvoice(UUID id) {
        ProformaInvoice proformaInvoice = proformaInvoiceRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Proforma invoice not found"));
        TenantGuard.assertOwned(proformaInvoice);
        return proformaInvoice;
    }
}
