package com.shvoy.payments.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.payments.domain.InvoiceMatchResult;

/** No derived queries — filter by PO/invoice over findAll(), the codebase convention. */
public interface InvoiceMatchResultRepository extends JpaRepository<InvoiceMatchResult, UUID> {
}
