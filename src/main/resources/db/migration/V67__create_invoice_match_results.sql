-- 6.5 re-spec: the durable per-invoice match verdict. One current row per
-- invoice (replaced on re-evaluation). Carries the verdict independent of any
-- payment transition, which is what a ROLLING supplier needs — no per-PO
-- payment gating happens, but the per-shipment/invoice verdicts are recorded
-- here for the statement view to reconcile against. positionMatched flags a
-- loose AMOUNT reconciliation (the weakest signal).
CREATE TABLE invoice_match_results (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders (id),
    invoice_id UUID NOT NULL REFERENCES invoices (id),
    covers_type VARCHAR(20) NOT NULL,
    terms_type VARCHAR(20),
    passed BOOLEAN NOT NULL,
    position_matched BOOLEAN NOT NULL,
    expected_amount NUMERIC(19, 2),
    invoice_amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    detail VARCHAR(2000),
    policy_applied VARCHAR(20) NOT NULL,
    evaluated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_invoice_match_results_company_id ON invoice_match_results (company_id);
CREATE INDEX idx_invoice_match_results_purchase_order_id ON invoice_match_results (purchase_order_id);
CREATE UNIQUE INDEX idx_invoice_match_results_invoice_id ON invoice_match_results (invoice_id);
