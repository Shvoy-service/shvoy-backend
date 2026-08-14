package com.shvoy.reconciliation.repository;

import java.util.List;

import org.springframework.data.repository.Repository;

import com.shvoy.reconciliation.domain.ReconciliationAuditEvent;

/**
 * The append-only store for the reconciliation audit trail (Story 5.7).
 *
 * <p>Deliberately extends the bare {@link Repository} marker and declares
 * <strong>only</strong> append ({@link #save}) and read ({@link #findAll})
 * operations — no {@code delete}, {@code deleteById}, or {@code deleteAll} is
 * exposed. Combined with {@link ReconciliationAuditEvent} being construct-only
 * (no mutators), this means there is no application code path to alter or
 * remove a recorded audit event: the trail can only grow. That's the "enforced
 * at the model/repository level" immutability the story requires, not a
 * convention someone could quietly break by adding an update method later.
 */
public interface ReconciliationAuditEventRepository extends Repository<ReconciliationAuditEvent, java.util.UUID> {

    ReconciliationAuditEvent save(ReconciliationAuditEvent event);

    List<ReconciliationAuditEvent> findAll();
}
