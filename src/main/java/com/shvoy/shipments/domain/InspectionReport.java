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
 * One inspection of a consignment (Story 7.4 revised) — inspections are
 * <strong>repeatable</strong> (a rework leads to a re-inspection, a second
 * record), and the <em>latest</em> governs. Each carries its own outcome,
 * date, report file (S3), and notes; the full ordered set is the inspection
 * history, while {@link ShipmentConsignment} caches the latest outcome for the
 * gate and display.
 */
@Entity
@Table(name = "inspection_reports")
public class InspectionReport extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "consignment_id", nullable = false)
    private UUID consignmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private InspectionOutcome outcome;

    @Column(name = "reference", length = 100)
    private String reference;

    @Column(name = "inspection_date")
    private LocalDate inspectionDate;

    @Column(name = "report_s3_key", length = 500)
    private String reportS3Key;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InspectionReport() {
    }

    public InspectionReport(UUID consignmentId, InspectionOutcome outcome, String reference, LocalDate inspectionDate,
            String reportS3Key, String notes, UUID createdBy) {
        this.consignmentId = consignmentId;
        this.outcome = outcome;
        this.reference = reference;
        this.inspectionDate = inspectionDate;
        this.reportS3Key = reportS3Key;
        this.notes = notes;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getConsignmentId() {
        return consignmentId;
    }

    public InspectionOutcome getOutcome() {
        return outcome;
    }

    public String getReference() {
        return reference;
    }

    public LocalDate getInspectionDate() {
        return inspectionDate;
    }

    public String getReportS3Key() {
        return reportS3Key;
    }

    public String getNotes() {
        return notes;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
