package com.shvoy.shipments.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.shvoy.shipments.domain.ShipmentDocumentAuditEvent;

/**
 * Append-only store for the shipment document audit trail (Story 7.2) — same
 * structural immutability as {@code PaymentAuditEventRepository} (6.2) and
 * {@code ReconciliationAuditEventRepository} (5.7): extends the bare {@link
 * Repository} marker and declares only append ({@link #save}) and read ({@link
 * #findAll}); no delete/update path is exposed, and {@code
 * ShipmentDocumentAuditEvent} is construct-only, so an entry can never be
 * altered or removed by application code.
 */
public interface ShipmentDocumentAuditEventRepository extends Repository<ShipmentDocumentAuditEvent, UUID> {

    ShipmentDocumentAuditEvent save(ShipmentDocumentAuditEvent event);

    List<ShipmentDocumentAuditEvent> findAll();
}
