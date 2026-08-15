package com.shvoy.payments.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.shvoy.payments.domain.DiscrepancyCaseAuditEvent;

/**
 * Append-only store for the discrepancy case audit trail (Story 6.6) — same
 * structural immutability as the other audit trails: extends the bare {@link
 * Repository} marker and declares only append ({@link #save}) and read ({@link
 * #findAll}); no delete/update path is exposed, and the entity is construct-only.
 */
public interface DiscrepancyCaseAuditEventRepository extends Repository<DiscrepancyCaseAuditEvent, UUID> {

    DiscrepancyCaseAuditEvent save(DiscrepancyCaseAuditEvent event);

    List<DiscrepancyCaseAuditEvent> findAll();
}
