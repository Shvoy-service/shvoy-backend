CREATE TABLE shipments (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    bl_reference VARCHAR(100),
    bl_date DATE,
    ex_factory_date DATE,
    bl_document_s3_key VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_shipments_company_id ON shipments (company_id);
