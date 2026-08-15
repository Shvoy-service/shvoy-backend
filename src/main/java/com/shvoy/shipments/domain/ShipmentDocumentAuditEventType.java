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
    CONSIGNMENT_DETACHED
}
