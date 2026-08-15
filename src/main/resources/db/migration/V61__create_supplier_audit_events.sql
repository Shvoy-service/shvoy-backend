-- Supplier remodel: the append-only audit trail for the sensitive supplier
-- actions — validation/un-validation, target-term activation, and (loudly) any
-- bank-details change that reverts a VALIDATED supplier to PENDING. Same
-- construct-only, no-delete shape as the other audit trails. actor nullable for
-- any future system action.
CREATE TABLE supplier_audit_events (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    supplier_id UUID NOT NULL REFERENCES suppliers (id),
    event_type VARCHAR(40) NOT NULL,
    detail VARCHAR(2000),
    actor UUID REFERENCES users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_supplier_audit_events_company_id ON supplier_audit_events (company_id);
CREATE INDEX idx_supplier_audit_events_supplier_id ON supplier_audit_events (supplier_id);
