package com.shvoy.dashboard.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.dashboard.dto.DashboardResponse;
import com.shvoy.payments.service.PaymentDashboardService;
import com.shvoy.suppliers.dto.SupplierPriceWarning;
import com.shvoy.suppliers.service.PriceWarningService;

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

    /** The price-warning digest is capped like the payment rows; the full list is on the suppliers side. */
    private static final int PRICE_WARNING_CAP = 10;

    private final PaymentDashboardService paymentDashboardService;
    private final PriceWarningService priceWarningService;

    DashboardService(PaymentDashboardService paymentDashboardService, PriceWarningService priceWarningService) {
        this.paymentDashboardService = paymentDashboardService;
        this.priceWarningService = priceWarningService;
    }

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard() {
        // The rollup is ordered (expired-first, then earliest expiry) by the operation; the dashboard just caps it.
        List<SupplierPriceWarning> priceWarnings = priceWarningService.warnings().stream()
            .limit(PRICE_WARNING_CAP)
            .toList();
        return new DashboardResponse(
            paymentDashboardService.stats(),
            paymentDashboardService.digest(ROW_CAP),
            priceWarnings,
            List.of()); // 9.3's slot — empty until then
    }
}
