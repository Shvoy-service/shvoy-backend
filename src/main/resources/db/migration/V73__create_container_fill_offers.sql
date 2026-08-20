CREATE TABLE container_fill_offers (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    shipment_id UUID NOT NULL REFERENCES shipments (id),
    supplier_id UUID NOT NULL REFERENCES suppliers (id),
    spare_cbm NUMERIC(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    deadline TIMESTAMP WITH TIME ZONE,
    notes VARCHAR(2000),
    flagged_by UUID NOT NULL REFERENCES users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_container_fill_offers_company_id ON container_fill_offers (company_id);
CREATE INDEX idx_container_fill_offers_shipment_id ON container_fill_offers (shipment_id);
CREATE INDEX idx_container_fill_offers_status ON container_fill_offers (status);
