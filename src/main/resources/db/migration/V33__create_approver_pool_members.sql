CREATE TABLE approver_pool_members (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    user_id UUID NOT NULL REFERENCES users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_approver_pool_members_company_id ON approver_pool_members (company_id);
CREATE UNIQUE INDEX idx_approver_pool_members_company_id_user_id ON approver_pool_members (company_id, user_id);
