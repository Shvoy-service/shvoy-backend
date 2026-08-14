package com.shvoy.reconciliation.domain;

/**
 * Whether an {@link ApprovalAction} approved or rejected a routed PI (Story
 * 5.5). Kept as an explicit type rather than a boolean so the immutable audit
 * row reads unambiguously and so a future third action (e.g. a distinct
 * "escalate") is an enum addition, not a schema change.
 */
public enum ApprovalActionType {
    APPROVE,
    REJECT
}
