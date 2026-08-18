package com.shvoy.shipments.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.shvoy.TenantScoped;

/**
 * The record raised when a physical arrival's counts differ from the provisional
 * GRN snapshot (Story 7.6) — per-SKU expected(GRN) vs arrived, in
 * {@link ArrivalDiscrepancyLine}s. <strong>Shipments-owned, deliberately not a
 * payments {@code DiscrepancyCase}.</strong> The roadmap's rule is absolute here:
 * an arrival mismatch is "a discrepancy record, not a reopened payment" — it never
 * touches the payment, the match, or closure. It's a credit-lane conversation
 * (6.7: an arrival shortfall's natural resolution is a {@code SHORT_SHIPMENT}
 * credit), kept as its own record so it doesn't conflate with a match discrepancy
 * (which resolves differently — see the {@code qc_failed} coexistence rule). It's
 * surfaced to the resolver lane via a payments-owned {@code ArrivalDiscrepancyEvent}
 * (the seam, following {@code QcFailureEvent}); resolution goes through the
 * existing credit-ledger endpoint.
 */
@Entity
@Table(name = "arrival_discrepancies")
public class ArrivalDiscrepancy extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "consignment_id", nullable = false)
    private UUID consignmentId;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "arrival_date", nullable = false)
    private LocalDate arrivalDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ArrivalDiscrepancy() {
    }

    public ArrivalDiscrepancy(UUID consignmentId, UUID purchaseOrderId, LocalDate arrivalDate) {
        this.consignmentId = consignmentId;
        this.purchaseOrderId = purchaseOrderId;
        this.arrivalDate = arrivalDate;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getConsignmentId() {
        return consignmentId;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public LocalDate getArrivalDate() {
        return arrivalDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
