package com.shvoy.onboarding.domain;

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

@Entity
@Table(name = "users")
public class User extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "verification_token")
    private String verificationToken;

    @Column(name = "verification_token_expires_at")
    private Instant verificationTokenExpiresAt;

    protected User() {
    }

    /**
     * New users start PENDING with no password — see RegistrationService for
     * why (registration and invite acceptance both work this way: a token
     * is issued via {@link #issueVerificationToken}, and
     * RegistrationService.activate sets the password and flips status once
     * it's verified — as a single conditional UPDATE, not through this
     * entity, so token consumption and activation stay atomic).
     */
    public User(String email, Role role) {
        this.email = email;
        this.role = role;
        this.status = UserStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void issueVerificationToken(String token, Instant expiresAt) {
        this.verificationToken = token;
        this.verificationTokenExpiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public Role getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
