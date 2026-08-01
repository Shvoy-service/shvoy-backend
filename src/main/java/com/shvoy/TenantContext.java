package com.shvoy;

import java.util.UUID;

/**
 * The single, shared source of truth for "which company is this request
 * acting as." Populated per-request by {@link TenantContextFilter} and
 * consulted by Hibernate (see {@link TenancyConfig}) to constrain every
 * query against a {@link TenantScoped} entity. Nothing else should
 * re-derive a company id independently.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_COMPANY_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID companyId) {
        CURRENT_COMPANY_ID.set(companyId);
    }

    /**
     * @throws IllegalStateException if no tenant has been established on this
     *                                thread — deliberately loud: querying
     *                                tenant-scoped data with no known tenant
     *                                is a bug, never a safe default.
     */
    public static UUID get() {
        UUID companyId = CURRENT_COMPANY_ID.get();
        if (companyId == null) {
            throw new IllegalStateException("No tenant company_id set on this thread");
        }
        return companyId;
    }

    public static void clear() {
        CURRENT_COMPANY_ID.remove();
    }
}
