package com.shvoy.payments.dto;

import java.time.LocalDate;

import org.springframework.modulith.NamedInterface;

import com.shvoy.Money;

/**
 * One Screen-1 payment digest row (Story 9.1) — the same row 6.3's queue
 * produces, narrowed to what the dashboard renders. {@code type} and {@code
 * status} are the enum names as strings (Screen-1's "Type"/"Status" columns), so
 * the cross-module contract needn't expose the payments domain enums. Produced by
 * reusing the queue's default view; the dashboard never re-derives ordering or
 * the {@code overdue} flag.
 */
@NamedInterface("payment-dashboard")
public record DashboardPaymentRowView(
    String poReference,
    String supplierName,
    String type,
    Money amount,
    LocalDate dueDate,
    String status,
    boolean overdue
) {
}
