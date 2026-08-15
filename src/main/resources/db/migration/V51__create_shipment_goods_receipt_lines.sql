-- Story 7.4: the provisional GRN's received quantities — a SNAPSHOT taken from
-- the packing list at receipt time, not a live reference (same provenance
-- principle as PO line prices and payment amounts). A later packing-list
-- correction does not silently rewrite an issued GRN; the GRN is amended
-- deliberately (audited) or the difference is a discrepancy conversation (7.6).
-- These per-SKU quantities are the substance the three-way match (6.5) compares.
CREATE TABLE shipment_goods_receipt_lines (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    consignment_id UUID NOT NULL REFERENCES shipment_consignments (id),
    sku_id UUID NOT NULL REFERENCES skus (id),
    received_quantity INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_shipment_goods_receipt_lines_company_id ON shipment_goods_receipt_lines (company_id);
CREATE INDEX idx_shipment_goods_receipt_lines_consignment_id ON shipment_goods_receipt_lines (consignment_id);
