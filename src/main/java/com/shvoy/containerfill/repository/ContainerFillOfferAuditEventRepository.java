package com.shvoy.containerfill.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.shvoy.containerfill.domain.ContainerFillOfferAuditEvent;

/**
 * Append-only audit store — the bare {@code Repository} marker exposing only
 * {@code save} + {@code findAll}, no update/delete path (same pattern as
 * {@code DiscrepancyCaseAuditEventRepository}).
 */
public interface ContainerFillOfferAuditEventRepository extends Repository<ContainerFillOfferAuditEvent, UUID> {

    ContainerFillOfferAuditEvent save(ContainerFillOfferAuditEvent event);

    List<ContainerFillOfferAuditEvent> findAll();
}
