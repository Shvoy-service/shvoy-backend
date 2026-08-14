package com.shvoy.reconciliation.dto;

import java.time.Instant;
import java.util.UUID;

import com.shvoy.reconciliation.domain.ApprovalActionType;

/** One recorded approve/reject action on a PI (Story 5.5) — an immutable audit entry. */
public record ApprovalActionResponse(
    UUID actorUserId,
    ApprovalActionType actionType,
    String comment,
    Instant at
) {
}
