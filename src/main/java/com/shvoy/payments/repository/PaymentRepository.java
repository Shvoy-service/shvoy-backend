package com.shvoy.payments.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.payments.domain.Payment;

/**
 * Deliberately no custom derived query methods — same convention as every
 * repository in this codebase; see SupplierRepository's Javadoc. Filtering
 * (by PO, status) happens in the service over findAll().
 */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
}
