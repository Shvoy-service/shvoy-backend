package com.shvoy.payments.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.shvoy.TenantScoped;

/** One PO line a {@code LINES}-coverage invoice claims, by SKU + quantity (invoice remodel). */
@Entity
@Table(name = "invoice_covered_lines")
public class InvoiceCoveredLine extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected InvoiceCoveredLine() {
    }

    public InvoiceCoveredLine(UUID invoiceId, UUID skuId, int quantity) {
        this.invoiceId = invoiceId;
        this.skuId = skuId;
        this.quantity = quantity;
    }

    public UUID getId() {
        return id;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public int getQuantity() {
        return quantity;
    }
}
