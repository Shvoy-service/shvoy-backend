CREATE TABLE credit_ledger_audit_events (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    credit_ledger_entry_id UUID NOT NULL REFERENCES credit_ledger_entries (id),
    event_type VARCHAR(20) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users (id),
    detail VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_credit_ledger_audit_events_company_id ON credit_ledger_audit_events (company_id);
CREATE INDEX idx_credit_ledger_audit_events_entry_id ON credit_ledger_audit_events (credit_ledger_entry_id);
