package com.shvoy.suppliers.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.suppliers.dto.DiscountTierResponse;
import com.shvoy.suppliers.dto.SetDiscountTiersRequest;
import com.shvoy.suppliers.service.DiscountTierService;

/**
 * Story 3.6. Full-replace only (no per-tier CRUD) — see
 * SetDiscountTiersRequest. Tiers arriving via the 3.5 price-file upload
 * path aren't wired up yet: no confirmed real-world price-file format
 * includes tier columns to parse (see docs/CONTRACT.md's Discount tiers
 * section) — this controller is the only way to set them for now.
 */
@RestController
@RequestMapping("/api/suppliers/{supplierId}/skus/{skuId}/prices/{priceId}/tiers")
class DiscountTierController {

    private final DiscountTierService discountTierService;

    DiscountTierController(DiscountTierService discountTierService) {
        this.discountTierService = discountTierService;
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    List<DiscountTierResponse> set(@PathVariable UUID supplierId, @PathVariable UUID skuId,
            @PathVariable UUID priceId, @Valid @RequestBody SetDiscountTiersRequest request) {
        return discountTierService.setTiers(supplierId, skuId, priceId, request.tiers());
    }

    @GetMapping
    List<DiscountTierResponse> get(@PathVariable UUID supplierId, @PathVariable UUID skuId,
            @PathVariable UUID priceId) {
        return discountTierService.getTiers(supplierId, skuId, priceId);
    }
}
