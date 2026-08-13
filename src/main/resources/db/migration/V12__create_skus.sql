CREATE TABLE skus (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    supplier_id UUID NOT NULL REFERENCES suppliers (id),
    code VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_skus_company_id ON skus (company_id);
CREATE INDEX idx_skus_supplier_id ON skus (supplier_id);
