CREATE TABLE proforma_invoices (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders (id),
    pi_reference VARCHAR(100) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    active BOOLEAN NOT NULL,
    logged_by UUID NOT NULL REFERENCES users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_proforma_invoices_company_id ON proforma_invoices (company_id);
CREATE INDEX idx_proforma_invoices_purchase_order_id ON proforma_invoices (purchase_order_id);
