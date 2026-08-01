package com.shvoy;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import org.hibernate.annotations.TenantId;

/**
 * Base class for entities that carry per-company data. {@code company_id} is
 * populated and enforced automatically by Hibernate (see TenancyConfig) —
 * every query against a subclass is transparently constrained to the current
 * tenant (from {@link TenantContext}), and the column is set automatically
 * on insert. Subclasses and callers never reference it directly; it exists
 * mainly so it's obvious at a glance which tables carry company data.
 */
@MappedSuperclass
public abstract class TenantScoped {

    @TenantId
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    public UUID getCompanyId() {
        return companyId;
    }
}
