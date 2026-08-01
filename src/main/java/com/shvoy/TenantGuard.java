package com.shvoy;

import java.util.UUID;

/**
 * Defense-in-depth ownership check for a tenant-scoped record fetched by id.
 * Hibernate's tenant filtering (see TenancyConfig) already prevents a
 * cross-tenant record from being loaded at all, so in normal operation this
 * can never actually trigger — it's here as an explicit, auditable guard for
 * any future code path that bypasses the standard repository (a native
 * query, a cache, etc), and to make the intent obvious at each call site.
 *
 * Throws the same {@link NotFoundException} used for a genuinely missing
 * record — a mismatch and an absence must be indistinguishable to the
 * caller, otherwise the response itself leaks whether the record exists.
 */
public final class TenantGuard {

    private TenantGuard() {
    }

    public static void assertOwned(TenantScoped entity) {
        assertOwnCompanyId(entity.getCompanyId());
    }

    /**
     * For a path {@code companyId} where the resource itself isn't
     * TenantScoped (e.g. Company, which IS the tenant rather than
     * belonging to one) — compares directly against TenantContext instead
     * of going through an entity's company_id.
     */
    public static void assertOwnCompanyId(UUID companyId) {
        if (!TenantContext.get().equals(companyId)) {
            throw new NotFoundException("Not found");
        }
    }
}
