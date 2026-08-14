package com.shvoy;

import java.util.UUID;

/**
 * The single source of truth for "which SHVOY user is this request acting
 * as" — the counterpart to {@link TenantContext} for identity rather than
 * tenancy. Populated per-request by {@link TenantContextFilter} alongside
 * {@code TenantContext} (same sources: the authenticated JWT's
 * {@code shvoy_user_id} claim in dev/prod, the {@code X-Debug-User-Id}
 * header in local/test), since both are resolved from the same request at
 * the same point.
 *
 * First needed in Story 4.4, for {@code PurchaseOrder.createdBy} — nothing
 * before that story needed to know "who is making this request," only
 * "which company." Deliberately loud when unset (mirrors
 * {@link TenantContext#get()}): a caller that needs the current user must
 * fail clearly, not silently attribute an action to nobody.
 */
public final class CurrentUserContext {

    private static final ThreadLocal<UUID> CURRENT_USER_ID = new ThreadLocal<>();

    private CurrentUserContext() {
    }

    public static void set(UUID userId) {
        CURRENT_USER_ID.set(userId);
    }

    /**
     * @throws IllegalStateException if no user has been established on this
     *                                thread.
     */
    public static UUID get() {
        UUID userId = CURRENT_USER_ID.get();
        if (userId == null) {
            throw new IllegalStateException("No current user id set on this thread");
        }
        return userId;
    }

    public static void clear() {
        CURRENT_USER_ID.remove();
    }
}
