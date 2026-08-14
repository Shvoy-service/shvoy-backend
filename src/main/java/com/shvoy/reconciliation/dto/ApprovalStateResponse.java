package com.shvoy.reconciliation.dto;

import java.util.List;
import java.util.UUID;

import com.shvoy.reconciliation.domain.ProformaInvoiceStatus;

/**
 * The approval progress for a routed PI (Story 5.5) — what Screen 4's approver
 * panel renders ("Approver 1 / 2 / 3 — 2 of 3 required"), and what the
 * approve/reject endpoints return so a caller sees the updated state without a
 * follow-up GET.
 *
 * <ul>
 *   <li>{@code requiresSignOff} — true when the PI has a price increase beyond
 *       tolerance, so the 2-of-N pool gate applies; false for the
 *       single-approver path (decrease / quantity / structural / currency).</li>
 *   <li>{@code requiredApprovals} — {@code N} from the 5.6 pool setting when
 *       {@code requiresSignOff}, else 1.</li>
 *   <li>{@code signedOffUserIds} — the distinct pool members who have approved
 *       so far (the ticked checkboxes); {@code approvalsRemaining} is how many
 *       more distinct sign-offs are needed.</li>
 *   <li>{@code approvable} — false when the PI cannot currently reach its
 *       threshold because the active pool is too small (members deactivated
 *       since routing); {@code blockedReason} explains it. The threshold is
 *       never auto-lowered — an ADMIN must fix the pool.</li>
 *   <li>{@code actions} — every recorded approve/reject, the immutable audit
 *       trail.</li>
 * </ul>
 */
public record ApprovalStateResponse(
    UUID proformaInvoiceId,
    ProformaInvoiceStatus status,
    boolean requiresSignOff,
    int requiredApprovals,
    int approvalsCollected,
    int approvalsRemaining,
    boolean thresholdMet,
    boolean approvable,
    String blockedReason,
    int eligiblePoolSize,
    List<UUID> signedOffUserIds,
    List<ApprovalActionResponse> actions
) {
}
