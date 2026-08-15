CREATE TABLE credit_ledger_entries (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders (id),
    amount_amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    cause VARCHAR(30) NOT NULL,
    cause_detail VARCHAR(1000),
    ncr_reference VARCHAR(100),
    target_invoice_id UUID REFERENCES invoices (id),
    status VARCHAR(20) NOT NULL,
    closure_reason VARCHAR(1000),
    logged_by UUID NOT NULL REFERENCES users (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_credit_ledger_entries_company_id ON credit_ledger_entries (company_id);
CREATE INDEX idx_credit_ledger_entries_purchase_order_id ON credit_ledger_entries (purchase_order_id);
