CREATE TABLE price_file_uploads (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    supplier_id UUID NOT NULL REFERENCES suppliers (id),
    s3_key VARCHAR(500) NOT NULL,
    row_count INTEGER NOT NULL,
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_price_file_uploads_company_id ON price_file_uploads (company_id);
CREATE INDEX idx_price_file_uploads_supplier_id ON price_file_uploads (supplier_id);
