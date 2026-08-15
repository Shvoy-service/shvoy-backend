package com.shvoy.payments.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.shvoy.payments.domain.CreditLedgerAuditEvent;

/**
 * Append-only store for the credit ledger's audit trail (Story 6.7) — same
 * structural immutability as the reconciliation (5.7) and payment (6.2) audit
 * repositories: extends the bare {@link Repository} marker exposing only append
 * ({@link #save}) and read ({@link #findAll}); no delete/update path exists, and
 * the entity is construct-only, so an entry can never be altered or removed.
 */
public interface CreditLedgerAuditEventRepository extends Repository<CreditLedgerAuditEvent, UUID> {

    CreditLedgerAuditEvent save(CreditLedgerAuditEvent event);

    List<CreditLedgerAuditEvent> findAll();
}
