package com.shvoy.purchaseorders.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.shvoy.purchaseorders.domain.PurchaseOrderAuditEvent;

/** Append-only store (PO-issuance gate) — only append + read, no delete/update path. */
public interface PurchaseOrderAuditEventRepository extends Repository<PurchaseOrderAuditEvent, UUID> {

    PurchaseOrderAuditEvent save(PurchaseOrderAuditEvent event);

    List<PurchaseOrderAuditEvent> findAll();
}
