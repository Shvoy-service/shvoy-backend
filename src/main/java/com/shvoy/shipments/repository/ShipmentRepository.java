package com.shvoy.shipments.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.shipments.domain.Shipment;

/**
 * Deliberately no custom derived query methods — same convention as every
 * repository in this codebase; see SupplierRepository's Javadoc. Every query is
 * tenant-constrained automatically (see TenancyConfig).
 */
public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {
}
