package com.shvoy.suppliers.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.shvoy.suppliers.dto.PriceResolutionResult;
import com.shvoy.suppliers.service.PriceResolutionService;

/**
 * Story 3.8. A pure read with no mutation, so — unlike every other
 * suppliers endpoint — there's no {@code ADMIN}/{@code PURCHASING}
 * restriction: any authenticated company user can preview a price, same
 * role rule as the other GETs in this module. Primarily meant for internal
 * consumption (Feature 4 pricing a PO, Feature 5 reconciling one), but
 * exposed here too so the PO screen can preview a price and so this story
 * has a black-box-testable surface.
 */
@RestController
@RequestMapping("/api/suppliers/{supplierId}/skus/{skuId}/price-resolution")
class PriceResolutionController {

    private final PriceResolutionService priceResolutionService;

    PriceResolutionController(PriceResolutionService priceResolutionService) {
        this.priceResolutionService = priceResolutionService;
    }

    @GetMapping
    PriceResolutionResult resolve(@PathVariable UUID supplierId, @PathVariable UUID skuId,
            @RequestParam int quantity,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        return priceResolutionService.resolve(supplierId, skuId, quantity, asOfDate);
    }
}
