package com.shvoy.suppliers.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.suppliers.domain.Sku;
import com.shvoy.suppliers.domain.SkuPrice;
import com.shvoy.suppliers.domain.SkuStatus;
import com.shvoy.suppliers.domain.Supplier;
import com.shvoy.suppliers.domain.SupplierStatus;
import com.shvoy.suppliers.dto.PriceFileStatus;
import com.shvoy.suppliers.dto.PriceResolutionResult;
import com.shvoy.suppliers.dto.SkuPriceWarning;
import com.shvoy.suppliers.dto.SupplierPriceWarning;
import com.shvoy.suppliers.repository.SkuPriceRepository;
import com.shvoy.suppliers.repository.SkuRepository;
import com.shvoy.suppliers.repository.SupplierRepository;

/**
 * Price-expiry warnings (Story 9.2) — the proactive end of the expired-price
 * control 4.5 enforces reactively. A derived, read-time rollup of each active
 * supplier's price-file health; no new state, a new query.
 *
 * <p><strong>The load-bearing reuse:</strong> "has a valid price today" is
 * defined by <em>3.8's resolver</em> ({@link PriceResolutionService#resolve}),
 * not a fresh {@code validTo < today} query here — so this warning, the 4.5
 * PO-creation gate, and the price resolver can never disagree about whether a
 * SKU is priceable (the open-ended-null edge, most likely). For "expiring soon"
 * we read the {@code validTo} of the price the resolver <em>already chose</em>
 * (by its {@code skuPriceId}), never re-deriving which window is current.
 */
@NamedInterface("price-warnings")
@Service
public class PriceWarningService {

    /**
     * The "expiring soon" horizon (Story 9.2) — a current price whose {@code
     * validTo} is within this many days (<strong>inclusive</strong>) warns.
     * MVP constant, not a per-account setting: constant first, config when
     * someone asks (the tolerance-configurability lesson). See docs/CONTRACT.md.
     */
    static final int WARNING_WINDOW_DAYS = 14;

    private final SupplierRepository supplierRepository;
    private final SkuRepository skuRepository;
    private final SkuPriceRepository skuPriceRepository;
    private final PriceResolutionService priceResolutionService;

    PriceWarningService(SupplierRepository supplierRepository, SkuRepository skuRepository,
            SkuPriceRepository skuPriceRepository, PriceResolutionService priceResolutionService) {
        this.supplierRepository = supplierRepository;
        this.skuRepository = skuRepository;
        this.skuPriceRepository = skuPriceRepository;
        this.priceResolutionService = priceResolutionService;
    }

    /**
     * Every active supplier in {@code EXPIRED} or {@code EXPIRING_SOON}, ordered
     * expired-first then by earliest expiry — the full list (Screen 2); the
     * dashboard caps it. {@code IN_DATE} suppliers are omitted (nothing to warn).
     */
    @Transactional(readOnly = true)
    public List<SupplierPriceWarning> warnings() {
        LocalDate today = LocalDate.now();
        return supplierRepository.findAll().stream()
            .filter(s -> s.getStatus() == SupplierStatus.ACTIVE)
            .map(supplier -> rollup(supplier, today))
            .filter(w -> w.status() != PriceFileStatus.IN_DATE)
            .sorted(Comparator
                .comparing((SupplierPriceWarning w) -> w.status() == PriceFileStatus.EXPIRED ? 0 : 1)
                .thenComparing(w -> w.earliestExpiry(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SupplierPriceWarning::supplierName, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    /** The per-SKU drill-down beneath one supplier's rollup — only the SKUs that actually warn. */
    @Transactional(readOnly = true)
    public List<SkuPriceWarning> skuWarnings(UUID supplierId) {
        LocalDate today = LocalDate.now();
        List<SkuPriceWarning> detail = new ArrayList<>();
        for (Sku sku : activeSkus(supplierId)) {
            classify(supplierId, sku, today).ifPresent(detail::add);
        }
        return detail;
    }

    // --- the rollup ---

    private SupplierPriceWarning rollup(Supplier supplier, LocalDate today) {
        int lapsed = 0;
        int neverPriced = 0;
        int expiring = 0;
        LocalDate earliestExpiry = null;

        for (Sku sku : activeSkus(supplier.getId())) {
            Optional<SkuPriceWarning> warning = classify(supplier.getId(), sku, today);
            if (warning.isEmpty()) {
                continue;
            }
            switch (warning.get().reason()) {
                case LAPSED -> lapsed++;
                case NEVER_PRICED -> neverPriced++;
                case EXPIRING -> {
                    expiring++;
                    LocalDate validTo = warning.get().validTo();
                    if (earliestExpiry == null || (validTo != null && validTo.isBefore(earliestExpiry))) {
                        earliestExpiry = validTo;
                    }
                }
            }
        }

        int expired = lapsed + neverPriced;
        PriceFileStatus status = expired > 0 ? PriceFileStatus.EXPIRED
            : expiring > 0 ? PriceFileStatus.EXPIRING_SOON
            : PriceFileStatus.IN_DATE;
        // EXPIRED dominates, so an expired supplier's earliest-expiry (an expiring date) is moot — clear it.
        return new SupplierPriceWarning(supplier.getId(), supplier.getName(), status,
            expired, neverPriced, expiring, status == PriceFileStatus.EXPIRED ? null : earliestExpiry);
    }

    /**
     * One SKU's warning, or empty when it's fine (a valid price beyond the window,
     * or open-ended). "Valid today" comes from the resolver; "expiring" reads the
     * resolved price's own {@code validTo}.
     */
    private Optional<SkuPriceWarning> classify(UUID supplierId, Sku sku, LocalDate today) {
        PriceResolutionResult resolution = priceResolutionService.resolve(supplierId, sku.getId(), 1, today);
        if (!resolution.priceFound()) {
            return Optional.of(new SkuPriceWarning(sku.getId(), sku.getCode(),
                resolution.everPriced() ? SkuPriceWarning.Reason.LAPSED : SkuPriceWarning.Reason.NEVER_PRICED, null));
        }
        // Priced today — read the validTo of the exact window the resolver chose (no independent re-query).
        LocalDate validTo = skuPriceRepository.findById(resolution.skuPriceId())
            .map(SkuPrice::getValidTo)
            .orElse(null);
        if (validTo == null) {
            return Optional.empty(); // open-ended — never warns
        }
        if (!validTo.isAfter(today.plusDays(WARNING_WINDOW_DAYS))) {
            return Optional.of(new SkuPriceWarning(sku.getId(), sku.getCode(), SkuPriceWarning.Reason.EXPIRING, validTo));
        }
        return Optional.empty(); // valid beyond the window
    }

    private List<Sku> activeSkus(UUID supplierId) {
        return skuRepository.findAll().stream()
            .filter(sku -> sku.getSupplierId().equals(supplierId) && sku.getStatus() == SkuStatus.ACTIVE)
            .toList();
    }
}
