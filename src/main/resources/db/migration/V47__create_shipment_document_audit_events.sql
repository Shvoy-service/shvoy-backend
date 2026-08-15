-- Story 7.2: the append-only audit trail for shipment documents. Dates drive
-- money timing (BL/ex-factory anchor a payment's due date), so a corrected date
-- is never a silent edit — old→new is recorded here, with who and when. Same
-- genuinely-append-only shape as payment_audit_events (6.2) and
-- reconciliation_audit_events (5.7): construct-only entity, no update/delete path.
-- consignment_id is nullable because BL/ex-factory changes are shipment-level.
CREATE TABLE shipment_document_audit_events (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    shipment_id UUID NOT NULL REFERENCES shipments (id),
    consignment_id UUID REFERENCES shipment_consignments (id),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders (id),
    event_type VARCHAR(40) NOT NULL,
    detail VARCHAR(2000),
    created_by UUID NOT NULL REFERENCES users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_shipment_document_audit_events_company_id ON shipment_document_audit_events (company_id);
CREATE INDEX idx_shipment_document_audit_events_shipment_id ON shipment_document_audit_events (shipment_id);
CREATE INDEX idx_shipment_document_audit_events_purchase_order_id ON shipment_document_audit_events (purchase_order_id);
