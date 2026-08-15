-- Invoice remodel: the specific PO lines (by SKU + claimed quantity) a
-- LINES-coverage invoice claims. Validated for ownership at entry; amounts judged
-- by the match (6.5 re-spec).
CREATE TABLE invoice_covered_lines (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    invoice_id UUID NOT NULL REFERENCES invoices (id),
    sku_id UUID NOT NULL REFERENCES skus (id),
    quantity INTEGER NOT NULL
);

CREATE INDEX idx_invoice_covered_lines_company_id ON invoice_covered_lines (company_id);
CREATE INDEX idx_invoice_covered_lines_invoice_id ON invoice_covered_lines (invoice_id);
