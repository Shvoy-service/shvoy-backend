-- Story 7.4 (revised): inspections are repeatable per consignment (rework leads
-- to a re-inspection), each with its own outcome (PASS/REWORK_REQUIRED/FAIL),
-- date, report file, and notes. The latest governs; ShipmentConsignment caches
-- the latest outcome for the gate. This is the full ordered inspection history.
CREATE TABLE inspection_reports (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    consignment_id UUID NOT NULL REFERENCES shipment_consignments (id),
    outcome VARCHAR(20) NOT NULL,
    reference VARCHAR(100),
    inspection_date DATE,
    report_s3_key VARCHAR(500),
    notes VARCHAR(2000),
    created_by UUID NOT NULL REFERENCES users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_inspection_reports_company_id ON inspection_reports (company_id);
CREATE INDEX idx_inspection_reports_consignment_id ON inspection_reports (consignment_id);
