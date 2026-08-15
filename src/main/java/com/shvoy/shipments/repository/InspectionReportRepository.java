package com.shvoy.shipments.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.shipments.domain.InspectionReport;

/**
 * Deliberately no custom derived query methods — same convention as every
 * repository here. "Latest inspection for a consignment" is computed in the
 * service over findAll(); every query is tenant-constrained automatically.
 */
public interface InspectionReportRepository extends JpaRepository<InspectionReport, UUID> {
}
