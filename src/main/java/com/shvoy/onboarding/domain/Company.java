package com.shvoy.onboarding.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The id is application-assigned (not {@code @GeneratedValue}), because
 * registration (see RegistrationService) must set {@link com.shvoy.TenantContext}
 * to the new company's id *before* saving anything — including this entity
 * itself — since Hibernate needs a resolvable tenant to open a session at
 * all once multi-tenancy is configured. Generating the id in Java lets the
 * tenant be established up front rather than depending on a DB-assigned
 * value that wouldn't exist yet.
 */
@Entity
@Table(name = "companies")
public class Company {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Company() {
    }

    public Company(UUID id, String name) {
        this.id = id;
        this.name = name;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
