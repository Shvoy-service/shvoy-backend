CREATE TABLE purchase_orders (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    supplier_id UUID NOT NULL REFERENCES suppliers (id),
    po_number VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    requested_etd DATE,
    created_by UUID NOT NULL REFERENCES users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_purchase_orders_company_id ON purchase_orders (company_id);
CREATE INDEX idx_purchase_orders_supplier_id ON purchase_orders (supplier_id);
CREATE UNIQUE INDEX idx_purchase_orders_company_id_po_number ON purchase_orders (company_id, po_number);
