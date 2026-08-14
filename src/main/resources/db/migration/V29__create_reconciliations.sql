CREATE TABLE reconciliations (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    proforma_invoice_id UUID NOT NULL REFERENCES proforma_invoices (id),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders (id),
    variance_basis VARCHAR(20) NOT NULL,
    price_file_as_of_date DATE,
    po_currency VARCHAR(3),
    pi_currency VARCHAR(3) NOT NULL,
    currency_mismatch BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_reconciliations_company_id ON reconciliations (company_id);
CREATE INDEX idx_reconciliations_proforma_invoice_id ON reconciliations (proforma_invoice_id);
CREATE INDEX idx_reconciliations_purchase_order_id ON reconciliations (purchase_order_id);
