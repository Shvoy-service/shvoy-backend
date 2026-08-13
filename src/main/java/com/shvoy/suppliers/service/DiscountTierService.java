package com.shvoy.suppliers.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
import com.shvoy.suppliers.dto.DiscountTierRequest;
import com.shvoy.suppliers.dto.DiscountTierResponse;
import com.shvoy.suppliers.repository.DiscountTierRepository;
import com.shvoy.suppliers.repository.SkuPriceRepository;
import com.shvoy.suppliers.repository.SkuRepository;
import com.shvoy.suppliers.repository.SupplierRepository;

/**
 * Story 3.6. Filters/matches in Java over findAll() rather than custom
 * repository query methods, same reasoning as SkuService/SupplierService —
 * see SupplierRepository's Javadoc.
 */
@Service
public class DiscountTierService {

    private final DiscountTierRepository discountTierRepository;
    private final SkuPriceRepository skuPriceRepository;
    private final SkuRepository skuRepository;
    private final SupplierRepository supplierRepository;

    DiscountTierService(DiscountTierRepository discountTierRepository, SkuPriceRepository skuPriceRepository,
            SkuRepository skuRepository, SupplierRepository supplierRepository) {
        this.discountTierRepository = discountTierRepository;
        this.skuPriceRepository = skuPriceRepository;
        this.skuRepository = skuRepository;
        this.supplierRepository = supplierRepository;
    }

    /**
     * Full-replace: the submitted set becomes the SkuPrice's entire tier
     * set. Existing rows are deleted and the new ones inserted fresh, all
     * inside one transaction — a DiscountTier is either freshly created or
     * gone, never edited in place (see DiscountTier's Javadoc).
     */
    @Transactional
    public List<DiscountTierResponse> setTiers(UUID supplierId, UUID skuId, UUID priceId,
            List<DiscountTierRequest> requests) {
        SkuPrice skuPrice = findOwnSkuPrice(supplierId, skuId, priceId);
        validateTiers(skuPrice, requests);

        discountTierRepository.findAll().stream()
            .filter(t -> t.getSkuPriceId().equals(priceId))
            .forEach(discountTierRepository::delete);

        List<DiscountTier> saved = requests.stream()
            .map(r -> discountTierRepository.save(new DiscountTier(priceId, r.quantityThreshold(), r.unitPriceAmount())))
            .toList();

        return toSortedResponses(saved, skuPrice);
    }

    @Transactional(readOnly = true)
    public List<DiscountTierResponse> getTiers(UUID supplierId, UUID skuId, UUID priceId) {
        SkuPrice skuPrice = findOwnSkuPrice(supplierId, skuId, priceId);
        List<DiscountTier> tiers = discountTierRepository.findAll().stream()
            .filter(t -> t.getSkuPriceId().equals(priceId))
            .toList();
        return toSortedResponses(tiers, skuPrice);
    }

    /**
     * Enforces the two invariants from Story 3.6: thresholds are unique
     * within the submitted set, and unit price is non-increasing as
     * threshold rises — including against the base SkuPrice's own unit
     * price, which is effectively the "tier" for quantities below the
     * lowest submitted threshold. Flagged as the MVP default (a volume
     * discount should never cost more per unit at a higher quantity) —
     * see docs/CONTRACT.md's Discount tiers section.
     */
    private static void validateTiers(SkuPrice skuPrice, List<DiscountTierRequest> requests) {
        Set<Integer> seenThresholds = new HashSet<>();
        for (DiscountTierRequest request : requests) {
            if (!seenThresholds.add(request.quantityThreshold())) {
                throw new ValidationException("Duplicate quantity threshold: " + request.quantityThreshold());
            }
        }

        BigDecimal previous = skuPrice.getUnitPrice().amount();
        for (DiscountTierRequest request : requests.stream()
                .sorted(Comparator.comparing(DiscountTierRequest::quantityThreshold))
                .toList()) {
            if (request.unitPriceAmount().compareTo(previous) > 0) {
                throw new ValidationException("Tier at quantity " + request.quantityThreshold()
                    + " must not have a higher unit price than the price for the quantity below it");
            }
            previous = request.unitPriceAmount();
        }
    }

    private SkuPrice findOwnSkuPrice(UUID supplierId, UUID skuId, UUID priceId) {
        Supplier supplier = supplierRepository.findById(supplierId)
            .orElseThrow(() -> new NotFoundException("Supplier not found"));
        TenantGuard.assertOwned(supplier);

        Sku sku = skuRepository.findById(skuId).orElseThrow(() -> new NotFoundException("SKU not found"));
        TenantGuard.assertOwned(sku);
        if (!sku.getSupplierId().equals(supplierId)) {
            throw new NotFoundException("SKU not found");
        }

        SkuPrice skuPrice = skuPriceRepository.findById(priceId)
            .orElseThrow(() -> new NotFoundException("Price not found"));
        TenantGuard.assertOwned(skuPrice);
        if (!skuPrice.getSkuId().equals(skuId)) {
            throw new NotFoundException("Price not found");
        }
        return skuPrice;
    }

    private static List<DiscountTierResponse> toSortedResponses(List<DiscountTier> tiers, SkuPrice skuPrice) {
        return tiers.stream()
            .sorted(Comparator.comparingInt(DiscountTier::getQuantityThreshold))
            .map(t -> toResponse(t, skuPrice))
            .toList();
    }

    private static DiscountTierResponse toResponse(DiscountTier tier, SkuPrice skuPrice) {
        UnitPrice unitPrice = new UnitPrice(tier.getUnitPriceAmount(), skuPrice.getUnitPrice().currency());
        return new DiscountTierResponse(tier.getId(), tier.getQuantityThreshold(), unitPrice, tier.getCreatedAt());
    }
}
