package com.shvoy.onboarding.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.onboarding.domain.ApproverPoolSettings;

/**
 * Deliberately no custom derived query methods — same convention as every
 * repository in this codebase. At most one row per company (tenant-filtered),
 * so the service resolves it via {@code findAll().stream().findFirst()}.
 */
public interface ApproverPoolSettingsRepository extends JpaRepository<ApproverPoolSettings, UUID> {
}
