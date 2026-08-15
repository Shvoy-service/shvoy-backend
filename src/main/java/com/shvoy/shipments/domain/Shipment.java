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
 * The BL-level record that a shipment is moving — one physical container /
 * Bill of Lading, potentially carrying goods for several POs across several
 * suppliers (the co-loading rule, Story 7.3). The per-PO detail — packing
 * list, inspection report, receipt lifecycle — lives on {@link
 * ShipmentConsignment}, one per PO; a shipment always has at least one.
 *
 * <p><strong>Why two entities.</strong> The naive model is one shipment per PO
 * with the BL as fields on it. The business rule breaks that: <em>"a single BL
 * can be linked to multiple POs across multiple suppliers; each linked PO
 * requires its own packing list before its portion can be receipted."</em> So
 * BL-level facts (this class) and per-PO facts ({@code ShipmentConsignment})
 * are genuinely different concepts. A non-co-loaded shipment is simply a
 * shipment with exactly one consignment — the common case costs nothing extra,
 * and per-consignment packing lists (7.3), per-portion receipting (7.4), and
 * arrival-vs-provisional per portion (7.6) all land naturally on this shape.
 * Do not flatten it back into one entity "for simplicity": the simplicity
 * would be borrowed against a certain remodel in 7.3.
 *
 * <p><strong>Co-loaded is derived, not stored:</strong> a shipment is co-loaded
 * when it has more than one consignment. Callers compute it by counting
 * consignments (7.3); there is deliberately no persisted flag.
 *
 * <p><strong>Anchor dates live here but publish later.</strong> {@code blDate}
 * and {@code exFactoryDate} are two of the four payment anchor events (the
 * enum in {@code suppliers}). The fields are modelled now; wiring the {@code
 * AnchorEventDateKnownEvent} publish when they are set is Story 7.2's job, not
 * this story's.
 *
 * <p><strong>When a shipment is created</strong> — at BL logging, or
 * proactively when a PO is sent — is Story 7.2's entry-point decision. This
 * story just makes the model able to hold either: the BL fields are nullable so
 * a record can exist before the BL is issued, but the BL reference is required
 * before a provisional GRN can be created (7.4's gate).
 */
@Entity
@Table(name = "shipments")
public class Shipment extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The Bill of Lading number. Null until the BL is issued; required before a provisional GRN (7.4). */
    @Column(name = "bl_reference", length = 100)
    private String blReference;

    /** The {@code BL} anchor event's date. Null until known; publishing the anchor event when it is set is 7.2. */
    @Column(name = "bl_date")
    private LocalDate blDate;

    /** The {@code EX_FACTORY} anchor event's date. Null until known; its natural home even though 7.2 wires the publish. */
    @Column(name = "ex_factory_date")
    private LocalDate exFactoryDate;

    /** S3 storage for the BL document. The upload flow that populates it is 7.2; this is only the storage field. */
    @Column(name = "bl_document_s3_key", length = 500)
    private String blDocumentS3Key;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Shipment() {
    }

    /**
     * Opens a shipment record. All BL details are optional at creation — a record
     * may be opened before the BL is issued (7.2's entry-point decision). The
     * consignments that give it PO-level meaning are added separately.
     */
    public Shipment(String blReference, LocalDate blDate, LocalDate exFactoryDate, String blDocumentS3Key) {
        this.blReference = blReference;
        this.blDate = blDate;
        this.exFactoryDate = exFactoryDate;
        this.blDocumentS3Key = blDocumentS3Key;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getBlReference() {
        return blReference;
    }

    public LocalDate getBlDate() {
        return blDate;
    }

    public LocalDate getExFactoryDate() {
        return exFactoryDate;
    }

    public String getBlDocumentS3Key() {
        return blDocumentS3Key;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
