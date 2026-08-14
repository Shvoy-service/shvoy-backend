package com.shvoy.purchaseorders.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.purchaseorders.domain.PurchaseOrderPriceOverride;

/**
 * Deliberately no custom derived query methods — same convention as every
 * repository in this module; see SupplierRepository's Javadoc.
 */
public interface PurchaseOrderPriceOverrideRepository extends JpaRepository<PurchaseOrderPriceOverride, UUID> {
}
