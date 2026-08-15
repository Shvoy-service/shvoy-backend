package com.shvoy.shipments.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.shvoy.TenantScoped;

/**
 * One PO's portion of a {@link Shipment} — the per-PO half of the two-entity
 * split the co-loading rule forces (see {@code Shipment}). A shipment carrying
 * goods for three POs has three consignments; the ordinary single-PO shipment
 * has exactly one. Each consignment carries its <em>own</em> packing list,
 * inspection report, and receipt lifecycle, because the rule is explicit that
 * "each linked PO requires its own packing list before its portion can be
 * receipted."
 *
 * <p><strong>The PO reference is a loose UUID, not a JPA association</strong> —
 * the same convention every entity in this codebase follows (e.g. {@code
 * Invoice#purchaseOrderId}): modules don't share object graphs, only ids and
 * events. At the DB level it is a real foreign key. The rule that the referenced
 * PO must be finalised ({@code GENERATED}/{@code SENT}) and belong to the same
 * company is enforced at creation time by the service that owns the entry point
 * (7.2), not on the entity; cross-tenant lookups already resolve to 404 via the
 * tenant filter.
 *
 * <p><strong>Cardinality.</strong> A consignment references exactly one PO. The
 * model permits one PO to appear in several consignments (a split order across
 * two containers), but the MVP workflow assumes one consignment per PO —
 * partial shipment is a flagged Product Owner question, bundled with partial
 * invoicing (6.4) because they are the same underlying commercial question and
 * their answers must be consistent.
 *
 * <p><strong>Fields that belong to later stories</strong> live here as data now
 * but are driven later: {@code confirmedEtd} is 7.5's ETD-delta concern (the
 * requested ETD stays on the PO; the delta is computed across the two, never
 * duplicated); {@code arrivalDate} is the {@code ARRIVAL} anchor 7.6 publishes;
 * the {@code receiptStatus} transitions are enforced by 7.4/7.6. The packing
 * list and inspection report S3 keys are storage fields only — the upload flow
 * is 7.2.
 */
@Entity
@Table(name = "shipment_consignments")
public class ShipmentConsignment extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shipment_id", nullable = false)
    private UUID shipmentId;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    /** This portion's packing list reference (structured field, Story 7.2). Per-consignment (co-loading rule). */
    @Column(name = "packing_list_reference", length = 100)
    private String packingListReference;

    /** This portion's packing list date (Story 7.2). Not an anchor date — captured for the record/GRN, not for payment timing. */
    @Column(name = "packing_list_date")
    private LocalDate packingListDate;

    /** S3 storage for this portion's packing list. Per-consignment, not per-shipment (co-loading rule). Upload flow is 7.2. */
    @Column(name = "packing_list_s3_key", length = 500)
    private String packingListS3Key;

    /** This portion's inspection report reference (Story 7.2). */
    @Column(name = "inspection_report_reference", length = 100)
    private String inspectionReportReference;

    /** This portion's inspection report date (Story 7.2). */
    @Column(name = "inspection_report_date")
    private LocalDate inspectionReportDate;

    /** The inspection outcome, recorded faithfully as stated — no cross-document verification here (that's the AI layer, Feature 10). */
    @Column(name = "inspection_report_outcome", length = 50)
    private String inspectionReportOutcome;

    /** S3 storage for this portion's inspection report. Per-consignment; upload flow is 7.2. */
    @Column(name = "inspection_report_s3_key", length = 500)
    private String inspectionReportS3Key;

    /** The confirmed ETD for this portion; the delta against the PO's requested ETD is 7.5's concern. Null until confirmed. */
    @Column(name = "confirmed_etd")
    private LocalDate confirmedEtd;

    /** The {@code ARRIVAL} anchor event's date. Null until physical arrival is confirmed (7.6). */
    @Column(name = "arrival_date")
    private LocalDate arrivalDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "receipt_status", nullable = false, length = 30)
    private ReceiptStatus receiptStatus;

    /**
     * Soft-delete flag for a mis-linked co-loaded consignment (Story 7.3). A
     * detach doesn't hard-delete — the row and its audit trail are retained
     * (evidence is never destroyed, same posture as a superseded document file);
     * detached consignments are simply excluded from the active set (views,
     * duplicate-attach checks, the document find-or-create path).
     */
    @Column(name = "detached", nullable = false)
    private boolean detached;

    /** When the provisional GRN was created (Story 7.4) — the actor/timestamp of the DOCUMENTS_PENDING → PROVISIONALLY_RECEIPTED move. */
    @Column(name = "provisionally_receipted_at")
    private Instant provisionallyReceiptedAt;

    @Column(name = "provisionally_receipted_by")
    private UUID provisionallyReceiptedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ShipmentConsignment() {
    }

    /**
     * Opens a consignment against a shipment and a PO. Its documents, ETD, and
     * arrival are all filled in later; it starts life {@link
     * ReceiptStatus#DOCUMENTS_PENDING}.
     */
    public ShipmentConsignment(UUID shipmentId, UUID purchaseOrderId) {
        this.shipmentId = shipmentId;
        this.purchaseOrderId = purchaseOrderId;
        this.receiptStatus = ReceiptStatus.DOCUMENTS_PENDING;
        this.createdAt = Instant.now();
    }

    /** Records (or corrects) this portion's packing list — Story 7.2. Not an anchor; status stays {@code DOCUMENTS_PENDING} until 7.4's gate. */
    public void recordPackingList(String reference, LocalDate date, String s3Key) {
        this.packingListReference = reference;
        this.packingListDate = date;
        this.packingListS3Key = s3Key;
        this.updatedAt = Instant.now();
    }

    /** Records (or corrects) this portion's inspection report — Story 7.2. Outcome stored as stated, no verification here. */
    public void recordInspectionReport(String reference, LocalDate date, String outcome, String s3Key) {
        this.inspectionReportReference = reference;
        this.inspectionReportDate = date;
        this.inspectionReportOutcome = outcome;
        this.inspectionReportS3Key = s3Key;
        this.updatedAt = Instant.now();
    }

    /**
     * The load-bearing co-loading rule (Story 7.3): <em>each linked PO requires
     * its <strong>own</strong> packing list before its portion can be
     * receipted.</em> A sibling consignment's documents never satisfy this — the
     * predicate reads only this consignment's own packing list. Exposed as a
     * named, reusable check because 7.4's provisional-GRN gate composes it with
     * whatever mandatory-document rule the Product Owners settle; the
     * packing-list-per-portion part is the piece the business rules state
     * unambiguously, so it's built now.
     */
    public boolean isReceiptEligible() {
        return packingListReference != null;
    }

    /** Soft-detach a mis-linked consignment while still {@link ReceiptStatus#DOCUMENTS_PENDING} (Story 7.3). Guarded by the service. */
    public void detach() {
        this.detached = true;
        this.updatedAt = Instant.now();
    }

    /**
     * Create the provisional GRN (Story 7.4): {@code DOCUMENTS_PENDING →
     * PROVISIONALLY_RECEIPTED}, explicitly <strong>without</strong> requiring
     * physical arrival. The service enforces the document gate and the from-state
     * before calling this; the transition guard here is defense-in-depth.
     */
    public void receiptProvisionally(UUID receiptedBy) {
        if (receiptStatus != ReceiptStatus.DOCUMENTS_PENDING) {
            throw new IllegalStateException("Consignment is not DOCUMENTS_PENDING: " + receiptStatus);
        }
        this.receiptStatus = ReceiptStatus.PROVISIONALLY_RECEIPTED;
        this.provisionallyReceiptedBy = receiptedBy;
        this.provisionallyReceiptedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getShipmentId() {
        return shipmentId;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public String getPackingListReference() {
        return packingListReference;
    }

    public LocalDate getPackingListDate() {
        return packingListDate;
    }

    public String getPackingListS3Key() {
        return packingListS3Key;
    }

    public String getInspectionReportReference() {
        return inspectionReportReference;
    }

    public LocalDate getInspectionReportDate() {
        return inspectionReportDate;
    }

    public String getInspectionReportOutcome() {
        return inspectionReportOutcome;
    }

    public String getInspectionReportS3Key() {
        return inspectionReportS3Key;
    }

    public LocalDate getConfirmedEtd() {
        return confirmedEtd;
    }

    public LocalDate getArrivalDate() {
        return arrivalDate;
    }

    public ReceiptStatus getReceiptStatus() {
        return receiptStatus;
    }

    public boolean isDetached() {
        return detached;
    }

    public Instant getProvisionallyReceiptedAt() {
        return provisionallyReceiptedAt;
    }

    public UUID getProvisionallyReceiptedBy() {
        return provisionallyReceiptedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
