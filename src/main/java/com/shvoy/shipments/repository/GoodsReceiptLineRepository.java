package com.shvoy.shipments.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.shipments.domain.GoodsReceiptLine;

/**
 * Deliberately no custom derived query methods — same convention as every
 * repository in this codebase. Filtering by consignment happens in the service
 * over findAll(); every query is tenant-constrained automatically.
 */
public interface GoodsReceiptLineRepository extends JpaRepository<GoodsReceiptLine, UUID> {
}
