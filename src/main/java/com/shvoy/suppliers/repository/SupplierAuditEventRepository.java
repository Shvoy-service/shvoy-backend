package com.shvoy.suppliers.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.shvoy.suppliers.domain.SupplierAuditEvent;

/**
 * Append-only supplier audit store (supplier remodel) — extends the bare {@link
 * Repository} marker exposing only append and read; no delete/update path, and
 * the entity is construct-only.
 */
public interface SupplierAuditEventRepository extends Repository<SupplierAuditEvent, UUID> {

    SupplierAuditEvent save(SupplierAuditEvent event);

    List<SupplierAuditEvent> findAll();
}
