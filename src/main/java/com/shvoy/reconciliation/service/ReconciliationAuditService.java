package com.shvoy.reconciliation.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.reconciliation.domain.ReconciliationAuditEvent;
import com.shvoy.reconciliation.domain.ReconciliationAuditEventType;
import com.shvoy.reconciliation.repository.ReconciliationAuditEventRepository;

/**
 * Appends to and reads the immutable reconciliation audit trail (Story 5.7).
 * The single seam every lifecycle step calls to record what happened — PI
 * logging (5.2), the comparison (5.3), the tolerance outcome (5.4), each
 * approval/rejection (5.5), and supersession — so the trail is written in one
 * consistent shape rather than each story inventing its own.
 *
 * <p>Append and read only: there is intentionally no update or delete method
 * here or on the repository (see {@code ReconciliationAuditEventRepository}).
 */
@Service
public class ReconciliationAuditService {

    private final ReconciliationAuditEventRepository auditEventRepository;

    ReconciliationAuditService(ReconciliationAuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    /**
     * Append one event. Runs in the caller's transaction so the audit entry
     * and the state change it records commit together — the trail can't end up
     * out of step with what actually happened.
     */
    public void record(UUID proformaInvoiceId, UUID reconciliationId, ReconciliationAuditEventType eventType,
            UUID actorUserId, String detail) {
        auditEventRepository.save(new ReconciliationAuditEvent(
            proformaInvoiceId, reconciliationId, eventType, actorUserId, detail));
    }

    /** The chronological trail for a PI — oldest first. Tenant-scoped like every other read. */
    @Transactional(readOnly = true)
    public List<ReconciliationAuditEvent> trailFor(UUID proformaInvoiceId) {
        return auditEventRepository.findAll().stream()
            .filter(event -> event.getProformaInvoiceId().equals(proformaInvoiceId))
            .sorted(Comparator.comparing(ReconciliationAuditEvent::getCreatedAt))
            .toList();
    }
}
