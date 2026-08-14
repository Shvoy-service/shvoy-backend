CREATE TABLE reconciliation_audit_events (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    proforma_invoice_id UUID NOT NULL REFERENCES proforma_invoices (id),
    reconciliation_id UUID REFERENCES reconciliations (id),
    event_type VARCHAR(30) NOT NULL,
    actor_user_id UUID REFERENCES users (id),
    detail VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_reconciliation_audit_events_company_id ON reconciliation_audit_events (company_id);
CREATE INDEX idx_reconciliation_audit_events_proforma_invoice_id ON reconciliation_audit_events (proforma_invoice_id);
