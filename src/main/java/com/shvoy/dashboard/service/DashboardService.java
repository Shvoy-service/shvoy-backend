package com.shvoy.dashboard.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.dashboard.dto.DashboardResponse;
import com.shvoy.payments.service.PaymentDashboardService;

/**
 * Composes Screen 1 (Story 9.1) — the dashboard module's reason to exist. It
 * assembles the response from the {@code payments} module's existing operations
 * (via {@link PaymentDashboardService}) and shapes them; it holds <strong>no
 * computation of its own</strong> — no overdue / due-window / open-case logic
 * lives here, deliberately (a second calculation is exactly the drift this
 * assembly-by-reuse prevents).
 */
@Service
public class DashboardService {

    /**
     * The dashboard shows a <em>digest</em>, not the full queue — the soonest-due
     * rows only; the full, paginated queue is one click away in the Payments view.
     */
    private static final int ROW_CAP = 10;

    private final PaymentDashboardService paymentDashboardService;

    DashboardService(PaymentDashboardService paymentDashboardService) {
        this.paymentDashboardService = paymentDashboardService;
    }

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard() {
        return new DashboardResponse(
            paymentDashboardService.stats(),
            paymentDashboardService.digest(ROW_CAP),
            List.of()); // 9.3's slot — empty until then
    }
}
