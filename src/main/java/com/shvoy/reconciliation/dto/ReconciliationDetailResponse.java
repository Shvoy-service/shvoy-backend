package com.shvoy.reconciliation.dto;

import java.util.List;

/**
 * Everything Screen 4 needs for one PI, in a single call (Story 5.7): the PI
 * itself (with its status and lines), the three-way comparison (legs, per-line
 * variance, outcome, the tolerance in force), the approval progress (the
 * conditional approver panel), and the immutable audit trail. Composed from
 * the existing per-concern responses rather than a parallel flattened shape, so
 * each stays the single source of truth for its own data.
 */
public record ReconciliationDetailResponse(
    ProformaInvoiceResponse proformaInvoice,
    ReconciliationResponse reconciliation,
    ApprovalStateResponse approvalState,
    List<ReconciliationAuditEventResponse> auditTrail
) {
}
