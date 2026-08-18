package com.shvoy.shipments.domain;

/**
 * The audit-trail vocabulary for shipment documents (Story 7.2). A document's
 * first logging is a {@code *_LOGGED} entry; a later change to a structured
 * field records a {@code DOCUMENT_FIELD_CORRECTED} entry (old→new), and
 * replacing the stored file records a {@code DOCUMENT_FILE_SUPERSEDED} entry
 * (the prior S3 object is retained, never deleted). Dates drive money timing,
 * so corrections are never silent.
 */
public enum ShipmentDocumentAuditEventType {
    BILL_OF_LADING_LOGGED,
    PACKING_LIST_LOGGED,
    INSPECTION_REPORT_LOGGED,
    DOCUMENT_FIELD_CORRECTED,
    DOCUMENT_FILE_SUPERSEDED,
    /** A PO was co-loaded onto an existing shipment as a new consignment (Story 7.3). */
    CONSIGNMENT_ATTACHED,
    /** A mis-linked consignment was detached while still {@code DOCUMENTS_PENDING} (Story 7.3). */
    CONSIGNMENT_DETACHED,
    /** A provisional GRN was created for a consignment (Story 7.4). */
    PROVISIONAL_GRN_CREATED,
    /** A provisional GRN's received quantities were amended before arrival (Story 7.4). */
    PROVISIONAL_GRN_AMENDED,
    /** The inspection-due flag was set (Story 7.4 revised). */
    INSPECTION_DUE_SET,
    /** The inspection-due flag was cleared — a waived control, so a reason is required (Story 7.4 revised). */
    INSPECTION_DUE_CLEARED,
    /** A REWORK_REQUIRED inspection held the consignment at the factory (Story 7.4 revised). */
    REWORK_HELD,
    /** A subsequent PASS released a rework hold (Story 7.4 revised). */
    REWORK_RELEASED,
    /** A provisional GRN was created despite a failed inspection — flagged qc_failed (Story 7.4 revised). */
    GRN_QC_FAILED,
    /** Physical arrival confirmed against the provisional GRN with no shortfall (Story 7.6). */
    ARRIVAL_CONFIRMED,
    /** Arrival confirmed but the counts differed from the GRN — a discrepancy record was raised (Story 7.6). */
    ARRIVAL_DISCREPANCY_RAISED,
    /** A confirmed arrival's date was corrected — the ARRIVAL anchor re-publishes (Story 7.6). */
    ARRIVAL_DATE_CORRECTED
}
