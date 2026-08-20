CREATE TABLE container_fill_offer_audit_events (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    offer_id UUID NOT NULL REFERENCES container_fill_offers (id),
    event_type VARCHAR(30) NOT NULL,
    detail VARCHAR(2000),
    actor UUID REFERENCES users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_container_fill_offer_audit_events_company_id ON container_fill_offer_audit_events (company_id);
CREATE INDEX idx_container_fill_offer_audit_events_offer_id ON container_fill_offer_audit_events (offer_id);
