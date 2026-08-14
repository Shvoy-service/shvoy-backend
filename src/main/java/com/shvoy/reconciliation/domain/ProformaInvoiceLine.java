package com.shvoy.reconciliation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.shvoy.TenantScoped;

/**
 * One confirmed SKU/quantity line on a {@link ProformaInvoice} — see Story
 * 5.1. Stores the supplier's confirmed unit price and quantity as the
 * source of truth; the line total reconciliation (5.3) compares it against
 * is a derived value computed at reconciliation time, not stored here — same
 * compute-from-source principle as elsewhere in this codebase.
 *
 * <strong>Correlation:</strong> links to its PO line by {@code skuId}, not a
 * direct PO-line foreign key. Real PIs don't always line up one-to-one with
 * the PO (a supplier might split, merge, omit, or add a line); the MVP
 * choice is to correlate by SKU and let reconciliation (5.3) detect and
 * surface the messy cases (a PI line for a SKU not on the PO, a PO line
 * with no matching PI line, duplicate SKUs) rather than assume them away.
 * This assumes SKUs are unique within a PO, which the PO line model already
 * guarantees.
 *
 * No currency column: a line's currency is always its parent PI's — lines
 * don't change currency — so it's read from there ({@code
 * ProformaInvoiceService} composes the wire-format {@code UnitPrice}),
 * never stored redundantly here where it could drift out of sync. Same
 * inheritance pattern as {@code DiscountTier} inheriting its parent {@code
 * SkuPrice}'s currency.
 *
 * No {@code updated_at}: a logged PI line is an immutable snapshot of what
 * the supplier confirmed — a correction re-issues a whole new {@link
 * ProformaInvoice} (see that class's Cardinality note) rather than editing
 * an existing line in place.
 */
@Entity
@Table(name = "proforma_invoice_lines")
public class ProformaInvoiceLine extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "proforma_invoice_id", nullable = false)
    private UUID proformaInvoiceId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "confirmed_unit_price_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal confirmedUnitPriceAmount;

    @Column(name = "confirmed_quantity", nullable = false)
    private int confirmedQuantity;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProformaInvoiceLine() {
    }

    public ProformaInvoiceLine(
            UUID proformaInvoiceId,
            UUID skuId,
            int lineNumber,
            BigDecimal confirmedUnitPriceAmount,
            int confirmedQuantity) {
        this.proformaInvoiceId = proformaInvoiceId;
        this.skuId = skuId;
        this.lineNumber = lineNumber;
        this.confirmedUnitPriceAmount = confirmedUnitPriceAmount;
        this.confirmedQuantity = confirmedQuantity;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getProformaInvoiceId() {
        return proformaInvoiceId;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public BigDecimal getConfirmedUnitPriceAmount() {
        return confirmedUnitPriceAmount;
    }

    public int getConfirmedQuantity() {
        return confirmedQuantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
