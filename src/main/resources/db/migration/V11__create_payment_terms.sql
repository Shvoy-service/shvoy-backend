CREATE TABLE payment_terms (
    supplier_id UUID PRIMARY KEY REFERENCES suppliers (id),
    company_id UUID NOT NULL REFERENCES companies (id),
    deposit_percentage NUMERIC(5, 2) NOT NULL,
    anchor_event VARCHAR(20) NOT NULL,
    days_offset INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_payment_terms_company_id ON payment_terms (company_id);
