package com.shvoy.dashboard.dto;

/**
 * The stable machine codes for Screen 1's banner alerts (Story 9.3) — the
 * error-code discipline applied to alerts: the frontend maps display copy and
 * routing from {@code code}, so {@code message} is a fallback, not the contract.
 * The list is deliberately short (two conditions); additions are one read-time
 * evaluator each, later, when asked.
 *
 * <ul>
 *   <li>{@code APPROVER_POOL_UNSATISFIABLE} — active approver-pool members &lt;
 *       the required sign-off count, so price-increase PIs can't be approved
 *       (the 5.5/5.6 stranded-guard, surfaced globally).</li>
 *   <li>{@code SUPPLIER_REVALIDATION_REQUIRED} — one or more suppliers reverted
 *       to {@code PENDING} (the bank-details rule) while carrying a live order.</li>
 * </ul>
 */
public enum AlertCode {
    APPROVER_POOL_UNSATISFIABLE,
    SUPPLIER_REVALIDATION_REQUIRED
}
