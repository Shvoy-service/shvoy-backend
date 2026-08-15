CREATE TABLE invoices (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders (id),
    invoice_reference VARCHAR(100) NOT NULL,
    amount_amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    invoice_date DATE NOT NULL,
    claimed_credit_amount NUMERIC(19, 2),
    claimed_credit_reference VARCHAR(100),
    status VARCHAR(20) NOT NULL,
    active BOOLEAN NOT NULL,
    logged_by UUID NOT NULL REFERENCES users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_invoices_company_id ON invoices (company_id);
CREATE INDEX idx_invoices_purchase_order_id ON invoices (purchase_order_id);
