package com.shvoy.suppliers.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
import com.shvoy.suppliers.dto.UpdateSkuRequest;
import com.shvoy.suppliers.service.SkuService;

/**
 * Story 3.5 — manual SKU/price entry. Bulk upload is PriceFileUploadController;
 * both funnel through SkuService's create/update/addPrice so the supersession
 * rule applies identically either way.
 */
@RestController
@RequestMapping("/api/suppliers/{supplierId}/skus")
class SkuController {

    private final SkuService skuService;

    SkuController(SkuService skuService) {
        this.skuService = skuService;
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
