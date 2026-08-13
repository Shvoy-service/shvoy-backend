package com.shvoy.suppliers.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.shvoy.suppliers.dto.PriceFileUploadResponse;
import com.shvoy.suppliers.service.PriceFileUploadService;

/**
 * Story 3.5 — bulk price-file upload. Same role restriction as manual
 * entry (SkuController); the canonical CSV template this parses against is
 * documented in docs/CONTRACT.md.
 */
@RestController
@RequestMapping("/api/suppliers/{supplierId}/price-file")
class PriceFileUploadController {

    private final PriceFileUploadService priceFileUploadService;

    PriceFileUploadController(PriceFileUploadService priceFileUploadService) {
        this.priceFileUploadService = priceFileUploadService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    ResponseEntity<PriceFileUploadResponse> upload(
            @PathVariable UUID supplierId, @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(priceFileUploadService.upload(supplierId, file));
    }
}
