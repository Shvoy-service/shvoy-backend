package com.shvoy.reconciliation.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.reconciliation.domain.ApprovalAction;

/**
 * Deliberately no custom derived query methods — same convention as every
 * repository in this codebase; see SupplierRepository's Javadoc. Filtering by
 * PI / action type happens in the service over findAll().
 */
public interface ApprovalActionRepository extends JpaRepository<ApprovalAction, UUID> {
}
