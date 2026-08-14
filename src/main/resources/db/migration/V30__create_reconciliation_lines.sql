CREATE TABLE reconciliation_lines (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    reconciliation_id UUID NOT NULL REFERENCES reconciliations (id),
    sku_id UUID NOT NULL REFERENCES skus (id),
    finding_type VARCHAR(20) NOT NULL,
    po_unit_price_amount NUMERIC(19, 4),
    po_quantity INTEGER,
    pi_unit_price_amount NUMERIC(19, 4),
    pi_quantity INTEGER,
    price_file_unit_price_amount NUMERIC(19, 4),
    price_file_price_found BOOLEAN,
    unit_price_variance_pct NUMERIC(12, 2),
    quantity_variance_pct NUMERIC(12, 2),
    quantity_variance_abs INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_reconciliation_lines_company_id ON reconciliation_lines (company_id);
CREATE INDEX idx_reconciliation_lines_reconciliation_id ON reconciliation_lines (reconciliation_id);
CREATE INDEX idx_reconciliation_lines_sku_id ON reconciliation_lines (sku_id);
