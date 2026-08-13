package com.shvoy.suppliers.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.suppliers.domain.SkuPrice;

/**
 * Deliberately no custom derived query methods — same convention as
 * SupplierRepository/PaymentTermsRepository; see SupplierRepository's
 * Javadoc for why. Resolving "the price valid on date X" for a SKU is the
 * price-resolution service's job (3.8), not a repository finder here.
 */
public interface SkuPriceRepository extends JpaRepository<SkuPrice, UUID> {
}
