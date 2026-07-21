CREATE TABLE companies (
    id UUID PRIMARY KEY
);

ALTER TABLE users
    ADD COLUMN company_id UUID NOT NULL REFERENCES companies (id);

CREATE INDEX idx_users_company_id ON users (company_id);
