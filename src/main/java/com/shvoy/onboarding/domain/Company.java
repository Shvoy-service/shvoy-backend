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

    @Column(name = "registered_address", length = 500)
    private String registeredAddress;

    @Column(name = "default_delivery_address", length = 500)
    private String defaultDeliveryAddress;

    @Column(name = "country", length = 100)
    private String country;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    @Column(name = "registration_number", length = 100)
    private String registrationNumber;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Company() {
    }

    public Company(UUID id, String name) {
        this.id = id;
        this.name = name;
        this.createdAt = Instant.now();
    }

    public void updateProfile(String registeredAddress, String defaultDeliveryAddress, String country,
            String contactEmail, String contactPhone, String registrationNumber) {
        this.registeredAddress = registeredAddress;
        this.defaultDeliveryAddress = defaultDeliveryAddress;
        this.country = country;
        this.contactEmail = contactEmail;
        this.contactPhone = contactPhone;
        this.registrationNumber = registrationNumber;
        this.updatedAt = Instant.now();
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

    public String getRegisteredAddress() {
        return registeredAddress;
    }

    public String getDefaultDeliveryAddress() {
        return defaultDeliveryAddress;
    }

    public String getCountry() {
        return country;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
