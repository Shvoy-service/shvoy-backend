package com.shvoy.onboarding.dto;

import java.util.UUID;

import com.shvoy.onboarding.domain.Role;
import com.shvoy.onboarding.domain.UserStatus;

/**
 * The current user's session context (`GET /api/me`) — who you are in SHVOY
 * terms, for the frontend to bootstrap its session (what to render, which
 * screens to gate). Resolved entirely from the authenticated token's identity;
 * no parameters.
 *
 * <p><strong>This is the single most type-bound response in the API</strong> —
 * the frontend generates its types from this shape and gates its whole UI off
 * {@code role}. Additions are safe; a rename/removal is breaking. A
 * serialisation test locks the exact field set.
 *
 * <p><strong>Display guidance, not authorization.</strong> The server re-checks
 * role / tenancy / status on every subsequent request regardless of what this
 * returned — that principle is why {@code /me} beat custom token claims, and it
 * is what stops anyone later "optimising" by trusting the client.
 *
 * <p>Deliberately excludes approver-pool membership (a contextual fetch at
 * sign-off time, checked server-side), any permissions list (the {@code role}
 * is the contract — enumerating permissions invites a parallel client-side authz
 * model), and anything sensitive (no {@code cognito_sub} — the client has the
 * token, it never needs the linkage). {@code companyName} is included so the
 * frontend renders company context without a second round-trip.
 */
public record MeResponse(
    UUID userId,
    String email,
    Role role,
    UUID companyId,
    String companyName,
    UserStatus status
) {
}
