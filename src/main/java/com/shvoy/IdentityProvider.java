package com.shvoy;

/**
 * Abstraction over the external identity system (Cognito in dev/prod, an
 * in-memory mock locally/in tests) that owns user credentials. SHVOY itself
 * never stores or validates a password — see RegistrationService.activate,
 * the only caller of this interface.
 */
public interface IdentityProvider {

    /**
     * Creates a new identity for {@code email} with {@code password} already
     * set as its permanent credential — the caller never needs a separate
     * "force change password on first login" step. Returns the provider's
     * stable identifier for the new identity (Cognito's {@code sub}), to be
     * stored on the corresponding SHVOY user row.
     */
    String createConfirmedUser(String email, String password);

    /**
     * Best-effort compensation for the case where the identity was created
     * here but the corresponding SHVOY-side write lost a race (see
     * RegistrationService.activate) — deletes the identity so a retry with
     * the same email isn't blocked. Callers must not treat failure of this
     * call as fatal: there is no way to make the two systems transactional
     * with each other, so a failed compensation is logged, not thrown.
     *
     * Takes the email, not the sub returned by {@link #createConfirmedUser}:
     * Cognito's admin APIs identify a user by its Username (which SHVOY sets
     * to the email at creation), not by sub.
     */
    void deleteUser(String email);
}
