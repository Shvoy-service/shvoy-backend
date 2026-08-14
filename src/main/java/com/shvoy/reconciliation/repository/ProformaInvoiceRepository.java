package com.shvoy.reconciliation.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.reconciliation.domain.ProformaInvoice;

/**
 * Deliberately no custom derived query methods — same convention as every
 * repository in this codebase; see SupplierRepository's Javadoc for why
 * (Spring Data validates a custom query method against Hibernate at
 * repository-bean-creation time, before any tenant can possibly exist).
 */
public interface ProformaInvoiceRepository extends JpaRepository<ProformaInvoice, UUID> {
}
