package com.shvoy.suppliers.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.suppliers.domain.Sku;

/**
 * Deliberately no custom derived query methods — same convention as
 * SupplierRepository/PaymentTermsRepository; see SupplierRepository's
 * Javadoc for why.
 */
public interface SkuRepository extends JpaRepository<Sku, UUID> {
}
