package com.shvoy.payments.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.payments.domain.InvoiceCoveredLine;

/** No derived queries — filter by invoice over findAll(), same convention as every repository here. */
public interface InvoiceCoveredLineRepository extends JpaRepository<InvoiceCoveredLine, UUID> {
}
