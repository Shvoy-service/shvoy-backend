package com.shvoy.reconciliation.dto;

import java.time.Instant;
import java.util.UUID;

import com.shvoy.reconciliation.domain.ReconciliationAuditEventType;

/**
 * One entry on the immutable reconciliation audit trail (Story 5.7).
 * {@code actorUserId} is null for system events (auto-confirm / route).
 */
public record ReconciliationAuditEventResponse(
    ReconciliationAuditEventType eventType,
    UUID actorUserId,
    String detail,
    Instant at
) {
}
