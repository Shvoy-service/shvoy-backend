ALTER TABLE reconciliations ADD COLUMN supplier_id UUID REFERENCES suppliers (id);

CREATE INDEX idx_reconciliations_supplier_id ON reconciliations (supplier_id);
