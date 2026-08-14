package com.shvoy.reconciliation.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.reconciliation.domain.ToleranceSetting;

/**
 * Deliberately no custom derived query methods — same convention as every
 * repository in this codebase; see SupplierRepository's Javadoc. There's at
 * most one row per company (tenant-filtered), so the service resolves it via
 * {@code findAll().stream().findFirst()}.
 */
public interface ToleranceSettingRepository extends JpaRepository<ToleranceSetting, UUID> {
}
