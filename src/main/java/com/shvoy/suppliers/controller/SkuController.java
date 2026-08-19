package com.shvoy.suppliers.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.suppliers.dto.CreateSkuRequest;
import com.shvoy.suppliers.dto.SkuPriceRequest;
import com.shvoy.suppliers.dto.SkuPriceResponse;
import com.shvoy.suppliers.dto.SkuResponse;
import com.shvoy.suppliers.dto.SkuWithPriceResponse;
import com.shvoy.suppliers.dto.SupplierSkuView;
import com.shvoy.suppliers.dto.UpdateSkuRequest;
import com.shvoy.suppliers.service.SkuService;

/**
 * Story 3.5 — manual SKU/price entry. Bulk upload is PriceFileUploadController;
 * both funnel through SkuService's create/update/addPrice so the supersession
 * rule applies identically either way.
 *
 * The {@code GET} list (supplier SKU read endpoint) is the supplier screen's
 * one-call read — a pure read, so (like PriceResolutionController and unlike
 * the mutating endpoints here) there's no {@code ADMIN}/{@code PURCHASING}
 * restriction, just authentication.
 */
@RestController
@RequestMapping("/api/suppliers/{supplierId}/skus")
class SkuController {

    private final SkuService skuService;

    SkuController(SkuService skuService) {
        this.skuService = skuService;
    }

    /**
     * A supplier's active SKUs, ordered by code, each with its current price
     * (with a derived {@code inDate} flag) and that price's discount tiers
     * inline. History is not included. Tenant-scoped: a cross-tenant or
     * unknown supplier is {@code 404}, same as every other read here.
     */
    @GetMapping
    List<SupplierSkuView> list(@PathVariable UUID supplierId) {
        return skuService.listSkus(supplierId);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    ResponseEntity<SkuWithPriceResponse> create(
            @PathVariable UUID supplierId, @Valid @RequestBody CreateSkuRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(skuService.createSku(supplierId, request));
    }

    @PutMapping("/{skuId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    SkuResponse update(@PathVariable UUID supplierId, @PathVariable UUID skuId,
            @Valid @RequestBody UpdateSkuRequest request) {
        return skuService.updateSku(supplierId, skuId, request);
    }

    @PostMapping("/{skuId}/prices")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    ResponseEntity<SkuPriceResponse> addPrice(@PathVariable UUID supplierId, @PathVariable UUID skuId,
            @Valid @RequestBody SkuPriceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(skuService.addPrice(supplierId, skuId, request));
    }
}
