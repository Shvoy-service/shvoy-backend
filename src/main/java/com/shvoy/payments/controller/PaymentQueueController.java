package com.shvoy.payments.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.shvoy.payments.domain.PaymentStatus;
import com.shvoy.payments.domain.PaymentType;
import com.shvoy.payments.dto.PaymentQueueResponse;
import com.shvoy.payments.dto.PaymentStatsResponse;
import com.shvoy.payments.service.PaymentQueueService;

/**
 * Story 6.3 — the payment queue and its dashboard stats. Read-only, open to
 * any authenticated company user: visibility is general (the dashboard is
 * everyone's landing page, and read-only/audit views everything); the finance
 * role only <em>acts</em> on payments in 6.8. No {@code {companyId}} path
 * segment — the caller's company comes from {@code TenantContext}.
 */
@RestController
@RequestMapping("/api/payments")
class PaymentQueueController {

    private final PaymentQueueService paymentQueueService;

    PaymentQueueController(PaymentQueueService paymentQueueService) {
        this.paymentQueueService = paymentQueueService;
    }

    /**
     * Defaults to the operational view: unpaid payments, soonest due first,
     * undated (awaiting anchor) grouped after. Optional {@code status}, {@code
     * type}, due-date range ({@code dueFrom}/{@code dueTo}), and {@code
     * overdue} narrow it; {@code page}/{@code size} page it.
     */
    @GetMapping
    PaymentQueueResponse queue(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) PaymentType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueTo,
            @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return paymentQueueService.getQueue(status, type, dueFrom, dueTo, overdue, page, size);
    }

    /** The dashboard aggregates — overdue count and due-within-5-days count — without fetching the full queue. */
    @GetMapping("/stats")
    PaymentStatsResponse stats() {
        return paymentQueueService.getStats();
    }
}
