package com.shvoy.shipments.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.shipments.domain.ShipmentConsignment;

/**
 * Deliberately no custom derived query methods — same convention as every
 * repository in this codebase; see SupplierRepository's Javadoc. Grouping
 * consignments by shipment (to derive co-loaded = count &gt; 1, 7.3) and
 * filtering by PO happen in the service over findAll(). Every query is
 * tenant-constrained automatically (see TenancyConfig).
 */
public interface ShipmentConsignmentRepository extends JpaRepository<ShipmentConsignment, UUID> {
}
