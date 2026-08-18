package com.shvoy.payments.service;

import java.util.List;

import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.payments.dto.DashboardPaymentRowView;
import com.shvoy.payments.dto.DashboardStatsView;
import com.shvoy.payments.dto.PaymentStatsResponse;

/**
 * The {@code payments} contribution to Screen 1 (Story 9.1) — the cross-module
 * surface the {@code dashboard} module composes. <strong>Pure delegation, no new
 * computation:</strong> {@link #stats()} reuses 6.3's {@code getStats()} (overdue
 * + due-within-5) and 6.6's {@code stats()} (open discrepancy cases); {@link
 * #digest(int)} reuses 6.3's default queue view (unpaid, due-date ascending,
 * undated last) capped at the caller's limit. If the dashboard's overdue count
 * ever disagreed with the payments screen, that's the bug this reuse prevents —
 * there is deliberately no second overdue / due-window / open-case logic here.
 */
@NamedInterface("payment-dashboard")
@Service
public class PaymentDashboardService {

    private final PaymentQueueService paymentQueueService;
    private final DiscrepancyViewService discrepancyViewService;

    PaymentDashboardService(PaymentQueueService paymentQueueService, DiscrepancyViewService discrepancyViewService) {
        this.paymentQueueService = paymentQueueService;
        this.discrepancyViewService = discrepancyViewService;
    }

    /** The three stat tiles — each delegated to the operation that already owns it. */
    @Transactional(readOnly = true)
    public DashboardStatsView stats() {
        PaymentStatsResponse payments = paymentQueueService.getStats();
        long openDiscrepancies = discrepancyViewService.stats().openCaseCount();
        return new DashboardStatsView(payments.overdueCount(), payments.dueWithin5DaysCount(), openDiscrepancies);
    }

    /** The capped payment digest — the exact rows 6.3's default queue view produces, narrowed and limited. */
    @Transactional(readOnly = true)
    public List<DashboardPaymentRowView> digest(int limit) {
        return paymentQueueService.getQueue(null, null, null, null, false, 0, limit).payments().stream()
            .map(row -> new DashboardPaymentRowView(
                row.poReference(),
                row.supplierName(),
                row.type().name(),
                row.amount(),
                row.dueDate(),
                row.status().name(),
                row.overdue()))
            .toList();
    }
}
