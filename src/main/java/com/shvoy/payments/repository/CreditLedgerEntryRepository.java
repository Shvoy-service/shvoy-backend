package com.shvoy.payments.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.payments.domain.CreditLedgerEntry;

/**
 * Deliberately no custom derived query methods — same convention as every
 * repository in this codebase; see SupplierRepository's Javadoc. Filtering
 * (by status, PO) happens in the service over findAll().
 */
public interface CreditLedgerEntryRepository extends JpaRepository<CreditLedgerEntry, UUID> {
}
