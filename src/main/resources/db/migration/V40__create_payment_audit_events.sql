CREATE TABLE payment_audit_events (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    payment_id UUID NOT NULL REFERENCES payments (id),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders (id),
    event_type VARCHAR(30) NOT NULL,
    detail VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_payment_audit_events_company_id ON payment_audit_events (company_id);
CREATE INDEX idx_payment_audit_events_payment_id ON payment_audit_events (payment_id);
