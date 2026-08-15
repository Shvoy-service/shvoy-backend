package com.shvoy.payments.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.shvoy.payments.domain.CreditLedgerStatus;
import com.shvoy.payments.dto.CancelCreditRequest;
import com.shvoy.payments.dto.CreditLedgerEntryResponse;
import com.shvoy.payments.dto.CreditLedgerStatsResponse;
import com.shvoy.payments.dto.LogCreditRequest;
import com.shvoy.payments.service.CreditLedgerService;

/**
 * Story 6.7 — the discrepancy / credit ledger. No {@code {companyId}} path
 * segment — the caller's company comes from {@code TenantContext}.
 *
 * Logging a credit is {@code PURCHASING}/{@code FINANCE}/{@code ADMIN}
 * (Purchasing resolves discrepancies, Finance owns payment review); cancelling
 * is the tighter {@code FINANCE}/{@code ADMIN} (closing a financial record).
 * Reads are open to any authenticated company user. There is deliberately no
 * update endpoint — amount and cause are immutable, a correction is
 * cancel-and-relog.
 */
@RestController
@RequestMapping("/api/credit-ledger")
class CreditLedgerController {

    private final CreditLedgerService creditLedgerService;

    CreditLedgerController(CreditLedgerService creditLedgerService) {
        this.creditLedgerService = creditLedgerService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING', 'FINANCE')")
    ResponseEntity<CreditLedgerEntryResponse> log(@Valid @RequestBody LogCreditRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(creditLedgerService.log(request));
    }

    /** Default (no {@code status}) lists OPEN entries; {@code status}/{@code purchaseOrderId} narrow it. */
    @GetMapping
    List<CreditLedgerEntryResponse> list(
            @RequestParam(required = false) CreditLedgerStatus status,
            @RequestParam(required = false) UUID purchaseOrderId) {
        return creditLedgerService.list(status, purchaseOrderId);
    }

    @GetMapping("/stats")
    CreditLedgerStatsResponse stats() {
        return new CreditLedgerStatsResponse(creditLedgerService.openCount());
    }

    @GetMapping("/{id}")
    CreditLedgerEntryResponse get(@PathVariable UUID id) {
        return creditLedgerService.get(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
    CreditLedgerEntryResponse cancel(@PathVariable UUID id, @Valid @RequestBody CancelCreditRequest request) {
        return creditLedgerService.cancel(id, request.reason());
    }
}
