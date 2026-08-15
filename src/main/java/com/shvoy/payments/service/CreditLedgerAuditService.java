package com.shvoy.payments.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.payments.domain.CreditLedgerAuditEvent;
import com.shvoy.payments.domain.CreditLedgerAuditEventType;
import com.shvoy.payments.repository.CreditLedgerAuditEventRepository;

/**
 * Appends to and reads the immutable credit-ledger audit trail (Story 6.7).
 * Append and read only — no update or delete path here or on the repository.
 */
@Service
public class CreditLedgerAuditService {

    private final CreditLedgerAuditEventRepository auditEventRepository;

    CreditLedgerAuditService(CreditLedgerAuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    /** Runs in the caller's transaction, so the audit entry commits with the transition it records. */
    public void record(UUID creditLedgerEntryId, CreditLedgerAuditEventType eventType, UUID actorUserId, String detail) {
        auditEventRepository.save(new CreditLedgerAuditEvent(creditLedgerEntryId, eventType, actorUserId, detail));
    }

    @Transactional(readOnly = true)
    public List<CreditLedgerAuditEvent> trailFor(UUID creditLedgerEntryId) {
        return auditEventRepository.findAll().stream()
            .filter(event -> event.getCreditLedgerEntryId().equals(creditLedgerEntryId))
            .sorted(Comparator.comparing(CreditLedgerAuditEvent::getCreatedAt))
            .toList();
    }
}
