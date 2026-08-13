package com.shvoy.suppliers.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.suppliers.domain.PaymentTerms;

/**
 * Deliberately no custom derived query methods — same convention as
 * SupplierRepository. PaymentTerms is keyed directly by supplier id, so
 * {@code findById(supplierId)} (a plain inherited JpaRepository method,
 * validated safely at startup) is all any caller ever needs — no
 * {@code findBySupplierId} to write in the first place.
 */
public interface PaymentTermsRepository extends JpaRepository<PaymentTerms, UUID> {
}
