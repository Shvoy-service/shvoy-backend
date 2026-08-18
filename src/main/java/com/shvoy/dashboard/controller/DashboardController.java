package com.shvoy.dashboard.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shvoy.dashboard.dto.DashboardResponse;
import com.shvoy.dashboard.service.DashboardService;

/**
 * Screen 1 in one call (Story 9.1) — {@code GET /api/dashboard}. Open to
 * <strong>every authenticated role</strong> ({@code READ_ONLY} included): the
 * dashboard is everyone's landing page, so there's no role gating on this read.
 * Tenant-scoped through the reused operations.
 */
@RestController
class DashboardController {

    private final DashboardService dashboardService;

    DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/api/dashboard")
    DashboardResponse get() {
        return dashboardService.getDashboard();
    }
}
