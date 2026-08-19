package com.shvoy.dashboard.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.TenantContext;
import com.shvoy.dashboard.dto.AlertCode;
import com.shvoy.dashboard.dto.AlertSeverity;
import com.shvoy.dashboard.dto.DashboardAlert;
import com.shvoy.onboarding.dto.ApproverPoolResponse;
import com.shvoy.onboarding.service.ApproverPoolService;
import com.shvoy.purchaseorders.service.PurchaseOrderService;
import com.shvoy.suppliers.dto.SupplierSummary;
import com.shvoy.suppliers.service.SupplierService;

/**
 * Screen 1's system-alert banner (Story 9.3) — the smallest thing that fills the
 * slot: <strong>two read-time checks over existing state, no framework.</strong>
 * No alert table, no lifecycle, no acknowledge/dismiss, no scheduler. Each alert
 * is derived on every dashboard read and vanishes the moment its condition
 * clears. Everything is composed from operations other modules already own; this
 * service adds no state of its own.
 */
@Service
class AlertService {

    private final ApproverPoolService approverPoolService;
    private final SupplierService supplierService;
    private final PurchaseOrderService purchaseOrderService;

    AlertService(ApproverPoolService approverPoolService, SupplierService supplierService,
            PurchaseOrderService purchaseOrderService) {
        this.approverPoolService = approverPoolService;
        this.supplierService = supplierService;
        this.purchaseOrderService = purchaseOrderService;
    }

    /** The current alerts, ordered by severity then code — realistically 0–2; empty is the healthy norm. */
    @Transactional(readOnly = true)
    public List<DashboardAlert> alerts() {
        List<DashboardAlert> alerts = new ArrayList<>();
        approverPoolUnsatisfiable().ifPresent(alerts::add);
        supplierRevalidationRequired().ifPresent(alerts::add);
        alerts.sort(Comparator.comparing(DashboardAlert::severity).thenComparing(DashboardAlert::code));
        return alerts;
    }

    /**
     * A pool that <em>has members but fewer than required</em> → price-increase
     * PIs can't reach their sign-off count (the 5.5/5.6 stranded case: a member
     * got deactivated below N). A company that has never added an approver isn't
     * nagged — that's an unconfigured pool (onboarding's concern), not a stranded
     * one, and every fresh company would otherwise sit permanently amber.
     */
    private java.util.Optional<DashboardAlert> approverPoolUnsatisfiable() {
        ApproverPoolResponse pool = approverPoolService.getPool(TenantContext.get());
        if (pool.eligibleMemberCount() == 0 || pool.eligibleMemberCount() >= pool.requiredSignOffCount()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new DashboardAlert(
            AlertCode.APPROVER_POOL_UNSATISFIABLE, AlertSeverity.WARNING,
            "The approver pool has " + pool.eligibleMemberCount() + " active member(s) but requires "
                + pool.requiredSignOffCount() + " sign-off(s) — price-increase approvals are blocked until it's topped up.",
            "/settings/approvers"));
    }

    /** Suppliers reverted to PENDING (bank-details rule) that still carry a live order → re-validate before paying. */
    private java.util.Optional<DashboardAlert> supplierRevalidationRequired() {
        Set<UUID> withOpenOrders = purchaseOrderService.supplierIdsWithOpenPurchaseOrders();
        long count = supplierService.pendingSuppliers().stream()
            .map(SupplierSummary::id)
            .filter(withOpenOrders::contains)
            .count();
        if (count == 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new DashboardAlert(
            AlertCode.SUPPLIER_REVALIDATION_REQUIRED, AlertSeverity.WARNING,
            count + " supplier(s) with a live order reverted to PENDING and need re-validating before their next payment.",
            "/suppliers?validationStatus=PENDING"));
    }
}
