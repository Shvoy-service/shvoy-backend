CREATE TABLE shipment_consignments (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    shipment_id UUID NOT NULL REFERENCES shipments (id),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders (id),
    packing_list_s3_key VARCHAR(500),
    inspection_report_s3_key VARCHAR(500),
    confirmed_etd DATE,
    arrival_date DATE,
    receipt_status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_shipment_consignments_company_id ON shipment_consignments (company_id);
CREATE INDEX idx_shipment_consignments_shipment_id ON shipment_consignments (shipment_id);
CREATE INDEX idx_shipment_consignments_purchase_order_id ON shipment_consignments (purchase_order_id);
