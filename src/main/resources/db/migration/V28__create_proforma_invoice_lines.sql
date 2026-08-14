CREATE TABLE proforma_invoice_lines (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    proforma_invoice_id UUID NOT NULL REFERENCES proforma_invoices (id),
    sku_id UUID NOT NULL REFERENCES skus (id),
    line_number INTEGER NOT NULL,
    confirmed_unit_price_amount NUMERIC(19, 4) NOT NULL,
    confirmed_quantity INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_proforma_invoice_lines_company_id ON proforma_invoice_lines (company_id);
CREATE INDEX idx_proforma_invoice_lines_proforma_invoice_id ON proforma_invoice_lines (proforma_invoice_id);
CREATE INDEX idx_proforma_invoice_lines_sku_id ON proforma_invoice_lines (sku_id);
