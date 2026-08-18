package com.shvoy.suppliers.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.shvoy.suppliers.dto.SkuPriceWarning;
import com.shvoy.suppliers.dto.SupplierPriceWarning;
import com.shvoy.suppliers.service.PriceWarningService;

/**
 * Price-expiry warnings (Story 9.2) — the proactive surface for the expired-price
 * control. The full, uncapped supplier list (Screen 2) and the per-supplier SKU
 * drill-down; the dashboard renders a capped digest of the same rollup. Reads
 * open to any authenticated company user (it's a status view, like the payment
 * queue); tenant-scoped.
 */
@RestController
class PriceWarningController {

    private final PriceWarningService priceWarningService;

    PriceWarningController(PriceWarningService priceWarningService) {
        this.priceWarningService = priceWarningService;
    }

    /** Every active supplier with an expired or expiring-soon price file — uncapped, expired-first. */
    @GetMapping("/api/suppliers/price-warnings")
    List<SupplierPriceWarning> warnings() {
        return priceWarningService.warnings();
    }

    /** The per-SKU drill-down beneath a supplier's rollup — which SKUs warn, and why (lapsed / never-priced / expiring). */
    @GetMapping("/api/suppliers/{supplierId}/price-warnings")
    List<SkuPriceWarning> skuWarnings(@PathVariable UUID supplierId) {
        return priceWarningService.skuWarnings(supplierId);
    }
}
