-- Story 7.4: per-SKU quantities on a consignment's packing list — the itemised
-- "what shipped" that the provisional GRN snapshots. Story 7.2 kept the packing
-- list's fields lean (reference/date/file); this is downstream logic (the GRN,
-- and the three-way match it feeds) declaring its need. Full-replaced when the
-- packing list is (re)logged.
CREATE TABLE shipment_packing_list_lines (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    consignment_id UUID NOT NULL REFERENCES shipment_consignments (id),
    sku_id UUID NOT NULL REFERENCES skus (id),
    quantity INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_shipment_packing_list_lines_company_id ON shipment_packing_list_lines (company_id);
CREATE INDEX idx_shipment_packing_list_lines_consignment_id ON shipment_packing_list_lines (consignment_id);
