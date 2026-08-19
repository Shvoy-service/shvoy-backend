package com.shvoy;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

/**
 * Append-only store for {@link SendRecord} (Story 9.4) — deliberately extends the
 * bare {@code Repository} with only save + findAll, no update or delete path, the
 * same immutable-audit shape as the payment/discrepancy audit trails.
 */
public interface SendRecordRepository extends Repository<SendRecord, UUID> {

    SendRecord save(SendRecord record);

    List<SendRecord> findAll();
}
