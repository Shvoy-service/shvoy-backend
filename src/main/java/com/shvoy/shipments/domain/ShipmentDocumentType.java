package com.shvoy.shipments.domain;

/**
 * The three shipment documents Screen 5 captures (Story 7.2). Used to route the
 * upload/download endpoints and to name the S3 folder a document is stored under.
 * BL is shipment-level; the packing list and inspection report are
 * per-consignment (the co-loading rule).
 */
public enum ShipmentDocumentType {
    BILL_OF_LADING,
    PACKING_LIST,
    INSPECTION_REPORT
}
