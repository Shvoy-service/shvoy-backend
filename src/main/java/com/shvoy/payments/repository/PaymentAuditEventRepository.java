package com.shvoy.payments.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.shvoy.payments.domain.PaymentAuditEvent;

/**
 * Append-only store for the payment audit trail (Story 6.2) — same structural
 * immutability as {@code ReconciliationAuditEventRepository} (5.7): extends the
 * bare {@link Repository} marker and declares only append ({@link #save}) and
 * read ({@link #findAll}); no delete/update path is exposed, and {@code
 * PaymentAuditEvent} is construct-only, so an entry can never be altered or
 * removed by application code.
 */
public interface PaymentAuditEventRepository extends Repository<PaymentAuditEvent, UUID> {

    PaymentAuditEvent save(PaymentAuditEvent event);

    List<PaymentAuditEvent> findAll();
}
