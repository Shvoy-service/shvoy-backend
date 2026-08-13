-- One row per company, tracking the last PO number issued to it — see
-- PoNumberGenerator. Owned by the purchaseorders module (not folded into
-- companies, which belongs to onboarding) even though it's keyed 1:1 with
-- a company.
CREATE TABLE po_number_counters (
    company_id UUID PRIMARY KEY REFERENCES companies (id),
    last_number INTEGER NOT NULL
);
