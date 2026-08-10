package com.shvoy.suppliers.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.shvoy.TenantScoped;

/**
 * The core supplier record — see Story 3.1. Deliberately lean: payment
 * terms (3.3), prices/SKUs (3.4+), discount tiers (3.6), and carton sizes
 * (3.7) are all separate entities in later stories, not columns here.
 *
 * Extends {@link TenantScoped} exactly like {@code User} does (see
 * onboarding.domain.User) — {@code company_id} is populated and enforced by
 * Hibernate automatically (see TenancyConfig), so every query against
 * suppliers is transparently constrained to the caller's company with no
 * per-query filtering.
 *
 * No money fields exist on this entity. When they arrive on later child
 * entities (price files, discount tiers), they follow the merged
 * string+currency wire format / BigDecimal convention — see {@link
 * com.shvoy.Money} and docs/CONTRACT.md — not a bare BigDecimal/double
 * column.
 */
@Entity
@Table(name = "suppliers")
public class Supplier extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupplierStatus status;

    @Column(length = 100)
    private String country;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Supplier() {
    }

    public Supplier(String name) {
        this.name = name;
        this.status = SupplierStatus.ACTIVE;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public SupplierStatus getStatus() {
        return status;
    }

    public String getCountry() {
        return country;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
