-- Story 6.6: the human half of the three-way match. When 6.5 blocks a payment,
-- a discrepancy case puts the mismatch in front of a named resolver. One case
-- per blocked payment (a re-fail updates it, never duplicates); a passing re-run
-- auto-resolves it. Claimable-queue model: claimed_by is the "named resolver"
-- the moment someone claims it. Resolution is one of corrected / credited (path
-- b, linked via credit_ledger_entry_id) / overridden (path c, with a reason), or
-- the case is marked DISPUTED (path d) while the payment stays BLOCKED.
CREATE TABLE discrepancy_cases (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    payment_id UUID NOT NULL REFERENCES payments (id),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders (id),
    status VARCHAR(20) NOT NULL,
    resolution_type VARCHAR(20),
    failure_detail VARCHAR(2000),
    credit_ledger_entry_id UUID REFERENCES credit_ledger_entries (id),
    claimed_by UUID REFERENCES users (id),
    claimed_at TIMESTAMP WITH TIME ZONE,
    resolution_reason VARCHAR(2000),
    resolved_by UUID REFERENCES users (id),
    resolved_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_discrepancy_cases_company_id ON discrepancy_cases (company_id);
CREATE INDEX idx_discrepancy_cases_payment_id ON discrepancy_cases (payment_id);
CREATE INDEX idx_discrepancy_cases_purchase_order_id ON discrepancy_cases (purchase_order_id);
CREATE INDEX idx_discrepancy_cases_status ON discrepancy_cases (status);
