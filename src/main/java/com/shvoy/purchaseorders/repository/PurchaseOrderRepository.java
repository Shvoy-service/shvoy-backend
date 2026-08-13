package com.shvoy.purchaseorders.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.purchaseorders.domain.PurchaseOrder;

/**
 * Deliberately no custom derived query methods — same convention as every
 * repository in the suppliers module; see SupplierRepository's Javadoc for
 * why (Spring Data validates a custom query method against Hibernate at
 * repository-bean-creation time, before any tenant can possibly exist).
 */
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {
}
