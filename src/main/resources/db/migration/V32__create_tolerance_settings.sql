CREATE TABLE tolerance_settings (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    tolerance_percentage NUMERIC(5, 2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX idx_tolerance_settings_company_id ON tolerance_settings (company_id);
