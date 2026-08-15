-- Supplier remodel: payment_terms goes from one flat row per supplier (PK
-- supplier_id) to a typed term record with its own id — a supplier can now hold
-- a current, a target, and retained history. TRANSFORM the data (not drop): each
-- existing row becomes a current term. We reuse the old supplier_id as the new
-- term id (exactly one term per supplier at migration time, so it stays unique),
-- which is portable across H2/Postgres with no SQL-side UUID generation and lets
-- suppliers.current_term_id be set to the supplier's own id in V60. Mapping:
-- deposit 0 -> ZERO_DEPOSIT (deposit_pct null); anything else -> DEPOSIT_BALANCE;
-- anchor + signed days carry over. Existing POs' snapshotted terms live on the
-- payment rows (6.2), which this never touches — the snapshot principle paying off.
CREATE TABLE payment_terms_new (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    supplier_id UUID NOT NULL REFERENCES suppliers (id),
    terms_type VARCHAR(20) NOT NULL,
    deposit_pct NUMERIC(4, 1),
    anchor_date_type VARCHAR(20) NOT NULL,
    days_from_anchor INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

INSERT INTO payment_terms_new
    (id, company_id, supplier_id, terms_type, deposit_pct, anchor_date_type, days_from_anchor, created_at, updated_at)
SELECT supplier_id, company_id, supplier_id,
       CASE WHEN deposit_percentage = 0 THEN 'ZERO_DEPOSIT' ELSE 'DEPOSIT_BALANCE' END,
       CASE WHEN deposit_percentage = 0 THEN NULL ELSE deposit_percentage END,
       anchor_event, days_offset, created_at, updated_at
FROM payment_terms;

DROP TABLE payment_terms;
ALTER TABLE payment_terms_new RENAME TO payment_terms;

CREATE INDEX idx_payment_terms_company_id ON payment_terms (company_id);
CREATE INDEX idx_payment_terms_supplier_id ON payment_terms (supplier_id);
