package com.shvoy.payments.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.payments.domain.GrnProjectionLine;

/**
 * Deliberately no custom derived query methods — same convention as every
 * repository in this codebase. Filtering by PO/consignment happens in the
 * service over findAll(); every query is tenant-constrained automatically.
 */
public interface GrnProjectionLineRepository extends JpaRepository<GrnProjectionLine, UUID> {
}
