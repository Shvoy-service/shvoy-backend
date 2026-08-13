package com.shvoy.purchaseorders.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.purchaseorders.domain.PurchaseOrderLine;

/**
 * Deliberately no custom derived query methods — same convention as every
 * repository in the suppliers module; see SupplierRepository's Javadoc.
 */
public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, UUID> {
}
