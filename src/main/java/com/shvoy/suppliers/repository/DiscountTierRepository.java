package com.shvoy.suppliers.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.suppliers.domain.DiscountTier;

/**
 * Deliberately no custom derived query methods — same convention as every
 * other repository in this module; see SupplierRepository's Javadoc.
 */
public interface DiscountTierRepository extends JpaRepository<DiscountTier, UUID> {
}
