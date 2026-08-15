-- Story 6.6: the case's immutable paper trail — the compliance record the
-- product's positioning sells. Same construct-only, no-delete-path shape as the
-- payment (6.2), reconciliation (5.7), and credit-ledger (6.7) trails. actor is
-- nullable: an auto-resolve on a passing re-run is a system action with no user.
CREATE TABLE discrepancy_case_audit_events (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    case_id UUID NOT NULL REFERENCES discrepancy_cases (id),
    event_type VARCHAR(30) NOT NULL,
    detail VARCHAR(2000),
    actor UUID REFERENCES users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_discrepancy_case_audit_events_company_id ON discrepancy_case_audit_events (company_id);
CREATE INDEX idx_discrepancy_case_audit_events_case_id ON discrepancy_case_audit_events (case_id);
