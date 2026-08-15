package com.shvoy.payments.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

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

import com.shvoy.payments.domain.DiscrepancyStatus;
import com.shvoy.payments.dto.CreditLedgerEntryResponse;
import com.shvoy.payments.dto.DiscrepancyCaseSummary;
import com.shvoy.payments.dto.DiscrepancyStatsResponse;
import com.shvoy.payments.dto.DiscrepancyViewResponse;
import com.shvoy.payments.dto.DisputeDiscrepancyRequest;
import com.shvoy.payments.dto.LogCaseCreditRequest;
import com.shvoy.payments.dto.OverrideDiscrepancyRequest;
import com.shvoy.payments.service.DiscrepancyCaseService;
import com.shvoy.payments.service.DiscrepancyViewService;

/**
 * Story 6.6 — the discrepancy resolver's endpoints. Routing lands with
 * <strong>Purchasing</strong> ("resolves discrepancies"): claim, agree a credit,
 * dispute. But <strong>overriding the payment control</strong> (path c) is
 * restricted to <strong>FINANCE/ADMIN</strong> — a Finance-grade decision, the
 * same segregation-of-duties instinct as self-approval prevention (5.5), and
 * flagged for the POs. Reads are open to any company user.
 */
@RestController
@RequestMapping("/api/discrepancies")
class DiscrepancyController {

    private final DiscrepancyViewService viewService;
    private final DiscrepancyCaseService caseService;

    DiscrepancyController(DiscrepancyViewService viewService, DiscrepancyCaseService caseService) {
        this.viewService = viewService;
        this.caseService = caseService;
    }

    @GetMapping
    List<DiscrepancyCaseSummary> list(@RequestParam(value = "status", required = false) DiscrepancyStatus status) {
        return viewService.list(status);
    }

    @GetMapping("/stats")
    DiscrepancyStatsResponse stats() {
        return viewService.stats();
    }

    @GetMapping("/{caseId}")
    DiscrepancyViewResponse get(@PathVariable UUID caseId) {
        return viewService.getView(caseId);
    }

    @PostMapping("/{caseId}/claim")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING')")
    DiscrepancyViewResponse claim(@PathVariable UUID caseId) {
        caseService.claim(caseId);
        return viewService.getView(caseId);
    }

    @PostMapping("/{caseId}/credit")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING', 'FINANCE')")
    ResponseEntity<CreditLedgerEntryResponse> logCredit(
            @PathVariable UUID caseId, @Valid @RequestBody LogCaseCreditRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(caseService.logCredit(caseId, request));
    }

    @PostMapping("/{caseId}/override")
    @PreAuthorize("hasAnyRole('ADMIN', 'FINANCE')")
    DiscrepancyViewResponse override(
            @PathVariable UUID caseId, @Valid @RequestBody OverrideDiscrepancyRequest request) {
        caseService.override(caseId, request);
        return viewService.getView(caseId);
    }

    @PostMapping("/{caseId}/dispute")
    @PreAuthorize("hasAnyRole('ADMIN', 'PURCHASING', 'FINANCE')")
    DiscrepancyViewResponse dispute(
            @PathVariable UUID caseId, @Valid @RequestBody DisputeDiscrepancyRequest request) {
        caseService.dispute(caseId, request);
        return viewService.getView(caseId);
    }
}
