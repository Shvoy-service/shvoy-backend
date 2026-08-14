CREATE TABLE approver_pool_settings (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    required_sign_off_count INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX idx_approver_pool_settings_company_id ON approver_pool_settings (company_id);
