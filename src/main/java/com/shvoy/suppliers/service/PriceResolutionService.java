package com.shvoy.suppliers.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.UnitPrice;
import com.shvoy.ValidationException;
import com.shvoy.suppliers.domain.DiscountTier;
import com.shvoy.suppliers.domain.Sku;
import com.shvoy.suppliers.domain.SkuPrice;
import com.shvoy.suppliers.domain.Supplier;
import com.shvoy.suppliers.dto.PriceResolutionResult;
import com.shvoy.suppliers.repository.DiscountTierRepository;
import com.shvoy.suppliers.repository.SkuPriceRepository;
import com.shvoy.suppliers.repository.SkuRepository;
import com.shvoy.suppliers.repository.SupplierRepository;

/**
 * Story 3.8 — the keystone of Feature 3: the one place that turns the data
 * every earlier story built (validity-windowed prices, tiers, carton size)
 * into an answer. Read-only and side-effect-free by construction — every
 * method here is {@code @Transactional(readOnly = true)}, nothing here ever
 * writes — and deterministic given the same inputs, so Feature 5 can re-run
 * a resolution for a past order date and reproduce the price quoted at the
 * time.
 *
 * Exposed as its own named interface, same as {@link PriceResolutionResult}
 * — see that type's Javadoc for why.
 */
@NamedInterface("price-resolution")
@Service
public class PriceResolutionService {

    private static final Logger log = LoggerFactory.getLogger(PriceResolutionService.class);

    private final SupplierRepository supplierRepository;
    private final SkuRepository skuRepository;
    private final SkuPriceRepository skuPriceRepository;
    private final DiscountTierRepository discountTierRepository;

    PriceResolutionService(SupplierRepository supplierRepository, SkuRepository skuRepository,
            SkuPriceRepository skuPriceRepository, DiscountTierRepository discountTierRepository) {
        this.supplierRepository = supplierRepository;
        this.skuRepository = skuRepository;
        this.skuPriceRepository = skuPriceRepository;
        this.discountTierRepository = discountTierRepository;
    }

    /**
     * Resolves the price, applied tier, and carton validity for
     * {@code quantity} units of {@code skuId} (from {@code supplierId}) as
     * of {@code asOfDate}. {@code asOfDate} is required, not defaulted to
     * today — prices are historical, so "the price" is meaningless without
     * a date (see the class Javadoc on determinism).
     */
    @Transactional(readOnly = true)
    public PriceResolutionResult resolve(UUID supplierId, UUID skuId, int quantity, LocalDate asOfDate) {
        Sku sku = findOwnSku(supplierId, skuId);
        if (quantity <= 0) {
            throw new ValidationException("quantity must be positive");
        }

        boolean cartonValid = sku.isCartonMultiple(quantity);
        int adjustedQuantity = sku.nearestCartonMultiple(quantity);

        List<SkuPrice> allPricesForSku = skuPriceRepository.findAll().stream()
            .filter(p -> p.getSkuId().equals(skuId))
            .toList();
        boolean everPriced = !allPricesForSku.isEmpty();

        Optional<SkuPrice> resolvedPrice = resolveApplicablePrice(skuId, allPricesForSku, asOfDate);
        if (resolvedPrice.isEmpty()) {
            return new PriceResolutionResult(false, null, null, null, asOfDate, cartonValid, adjustedQuantity, everPriced);
        }

        SkuPrice price = resolvedPrice.get();
        Optional<DiscountTier> appliedTier = resolveApplicableTier(price.getId(), quantity);
        UnitPrice unitPrice = appliedTier
            .map(tier -> new UnitPrice(tier.getUnitPriceAmount(), price.getUnitPrice().currency()))
            .orElseGet(price::getUnitPrice);
        Integer appliedTierThreshold = appliedTier.map(DiscountTier::getQuantityThreshold).orElse(null);

        return new PriceResolutionResult(true, price.getId(), unitPrice, appliedTierThreshold, asOfDate,
            cartonValid, adjustedQuantity, true);
    }

    /**
     * At most one SkuPrice should ever cover a given date — the 3.5
     * supersession rule keeps a SKU's prices non-overlapping. Defends
     * against that invariant somehow having been violated anyway (e.g. a
     * row inserted outside the normal write path) rather than assuming it
     * always holds: picks the row with the latest validFrom deterministically
     * and logs it as a data-integrity signal, since more than one match
     * means something upstream is broken, not a case to silently paper over.
     */
    private Optional<SkuPrice> resolveApplicablePrice(UUID skuId, List<SkuPrice> allPricesForSku, LocalDate asOfDate) {
        List<SkuPrice> candidates = allPricesForSku.stream()
            .filter(p -> p.isInDate(asOfDate))
            .toList();
        if (candidates.size() > 1) {
            log.warn("Data integrity: {} SkuPrice rows for SKU {} all cover {} — the non-overlapping "
                    + "supersession invariant (Story 3.5) has been violated; resolving to the latest validFrom",
                candidates.size(), skuId, asOfDate);
        }
        return candidates.stream().max(Comparator.comparing(SkuPrice::getValidFrom));
    }

    /**
     * The highest threshold at or below quantity — tiers are monotonically
     * non-increasing (3.6), so the highest applicable threshold is always
     * the best/correct price. Empty (base SkuPrice price applies) when
     * quantity falls below every tier's threshold, or the price has none.
     */
    private Optional<DiscountTier> resolveApplicableTier(UUID skuPriceId, int quantity) {
        return discountTierRepository.findAll().stream()
            .filter(t -> t.getSkuPriceId().equals(skuPriceId))
            .filter(t -> quantity >= t.getQuantityThreshold())
            .max(Comparator.comparingInt(DiscountTier::getQuantityThreshold));
    }

    private Sku findOwnSku(UUID supplierId, UUID skuId) {
        Supplier supplier = supplierRepository.findById(supplierId)
            .orElseThrow(() -> new NotFoundException("Supplier not found"));
        TenantGuard.assertOwned(supplier);

        Sku sku = skuRepository.findById(skuId).orElseThrow(() -> new NotFoundException("SKU not found"));
        TenantGuard.assertOwned(sku);
        if (!sku.getSupplierId().equals(supplierId)) {
            throw new NotFoundException("SKU not found");
        }
        return sku;
    }
}
