package com.shvoy.purchaseorders.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.purchaseorders.domain.PurchaseOrderPriceOverrideLine;

/**
 * Deliberately no custom derived query methods — same convention as every
 * repository in this module; see SupplierRepository's Javadoc.
 */
public interface PurchaseOrderPriceOverrideLineRepository extends JpaRepository<PurchaseOrderPriceOverrideLine, UUID> {
}
