package com.shvoy.payments.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.payments.domain.DiscrepancyCase;

/**
 * Deliberately no custom derived query methods — same convention as every
 * repository here. Filtering by payment/PO/status happens in the service over
 * findAll(); every query is tenant-constrained automatically.
 */
public interface DiscrepancyCaseRepository extends JpaRepository<DiscrepancyCase, UUID> {
}
