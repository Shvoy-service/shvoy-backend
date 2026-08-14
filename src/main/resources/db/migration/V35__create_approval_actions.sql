CREATE TABLE approval_actions (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    proforma_invoice_id UUID NOT NULL REFERENCES proforma_invoices (id),
    reconciliation_id UUID NOT NULL REFERENCES reconciliations (id),
    action_type VARCHAR(20) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users (id),
    comment VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_approval_actions_company_id ON approval_actions (company_id);
CREATE INDEX idx_approval_actions_proforma_invoice_id ON approval_actions (proforma_invoice_id);
