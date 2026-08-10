package com.shvoy.suppliers.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.suppliers.dto.SupplierRequest;
import com.shvoy.suppliers.dto.SupplierResponse;
import com.shvoy.suppliers.service.SupplierService;

/**
 * No {@code {companyId}} path segment, unlike onboarding's controllers
 * (e.g. CompanyProfileController): the caller's company always comes from
 * TenantContext (resolved from the authenticated JWT — see
 * TenantContextFilter/CognitoJwtAuthenticationConverter), never echoed back
 * through the URL or accepted from the request body — see SupplierRequest,
 * which has no company field to begin with.
 */
@RestController
@RequestMapping("/api/suppliers")
class SupplierController {

    private final SupplierService supplierService;

    SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    ResponseEntity<SupplierResponse> create(@Valid @RequestBody SupplierRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(supplierService.create(request));
    }

    /**
     * Active suppliers only by default, sorted by name — {@code
     * includeInactive=true} opts into seeing deactivated ones too. No
     * pagination: out of scope for the pilot's supplier counts (see Story
     * 3.2's scope).
     */
    @GetMapping
    List<SupplierResponse> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return supplierService.list(includeInactive);
    }

    @GetMapping("/{id}")
    SupplierResponse get(@PathVariable UUID id) {
        return supplierService.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    SupplierResponse update(@PathVariable UUID id, @Valid @RequestBody SupplierRequest request) {
        return supplierService.update(id, request);
    }

    /**
     * 200 with the deactivated supplier rather than 204 — same reasoning as
     * TeamController.deactivate: immediate confirmation of the new state,
     * no follow-up GET needed.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    SupplierResponse deactivate(@PathVariable UUID id) {
        return supplierService.deactivate(id);
    }
}
