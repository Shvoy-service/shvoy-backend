-- Story 7.6 — physical arrival confirmation. On a mismatch between arrived
-- counts and the provisional GRN snapshot, a discrepancy record is raised —
-- per-SKU expected(GRN) vs arrived, direction. This NEVER touches the payment,
-- the match, or closure: arrival differences are a credit-lane conversation
-- (6.7), not a reopened payment (the roadmap's core rule). The record is
-- shipments-owned; its resolution flows through the existing credit ledger.
CREATE TABLE arrival_discrepancies (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    consignment_id UUID NOT NULL REFERENCES shipment_consignments (id),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders (id),
    arrival_date DATE NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE arrival_discrepancy_lines (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    arrival_discrepancy_id UUID NOT NULL REFERENCES arrival_discrepancies (id),
    sku_id UUID NOT NULL REFERENCES skus (id),
    expected_quantity INTEGER NOT NULL,
    arrived_quantity INTEGER NOT NULL,
    direction VARCHAR(10) NOT NULL
);

CREATE INDEX idx_arrival_discrepancies_company_id ON arrival_discrepancies (company_id);
CREATE INDEX idx_arrival_discrepancies_consignment_id ON arrival_discrepancies (consignment_id);
CREATE INDEX idx_arrival_discrepancy_lines_company_id ON arrival_discrepancy_lines (company_id);
CREATE INDEX idx_arrival_discrepancy_lines_parent ON arrival_discrepancy_lines (arrival_discrepancy_id);
