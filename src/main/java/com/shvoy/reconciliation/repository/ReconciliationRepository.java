package com.shvoy.reconciliation.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.reconciliation.domain.Reconciliation;

/**
 * Deliberately no custom derived query methods — same convention as every
 * repository in this codebase; see SupplierRepository's Javadoc.
 */
public interface ReconciliationRepository extends JpaRepository<Reconciliation, UUID> {
}
