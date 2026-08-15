package com.shvoy.reconciliation.event;

import java.util.UUID;

import org.springframework.modulith.NamedInterface;

/**
 * "A PO's PI became confirmed" — published when a PI reaches {@code
 * AUTO_CONFIRMED} (5.4) or {@code APPROVED} (5.5). It's the trigger the
 * three-way match (6.5, in {@code payments}) subscribes to so a payment blocked
 * on a not-yet-confirmed PI re-evaluates the moment the PI is confirmed.
 *
 * <p><strong>Owned by the publisher.</strong> Unlike the shipments seam (where
 * an existing {@code shipments → payments} dependency forced a payments-owned
 * event), there is no {@code reconciliation ↔ payments} dependency, so this is
 * owned by {@code reconciliation} and {@code payments} depends on it — the same
 * single-direction shape as {@code PurchaseOrderGeneratedEvent}. A lightweight
 * trigger: it carries only the PO id; the match pulls the confirmed PI's detail
 * via {@code ProformaInvoiceMatchService}.
 */
@NamedInterface("reconciliation-events")
public record ProformaInvoiceConfirmedEvent(UUID purchaseOrderId) {
}
