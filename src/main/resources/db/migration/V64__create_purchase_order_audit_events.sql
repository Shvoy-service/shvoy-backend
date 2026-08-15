-- PO-issuance gate: append-only trail for the advisory flags — stamped at
-- generation, cleared when the contract reference / compliance cert lands later.
-- A PO that went out contract-pending is a matter of record.
CREATE TABLE purchase_order_audit_events (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders (id),
    event_type VARCHAR(40) NOT NULL,
    detail VARCHAR(2000),
    actor UUID REFERENCES users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_purchase_order_audit_events_company_id ON purchase_order_audit_events (company_id);
CREATE INDEX idx_purchase_order_audit_events_purchase_order_id ON purchase_order_audit_events (purchase_order_id);
