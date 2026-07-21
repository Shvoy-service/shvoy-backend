package com.shvoy.onboarding.domain;

import org.springframework.modulith.NamedInterface;

/**
 * Exposed as its own named interface so other modules can depend on the role
 * model directly without gaining access to the rest of the (otherwise
 * internal) onboarding.domain package.
 */
@NamedInterface("role")
public enum Role {
    ADMIN,
    PURCHASING,
    FINANCE,
    APPROVER,
    READ_ONLY
}
