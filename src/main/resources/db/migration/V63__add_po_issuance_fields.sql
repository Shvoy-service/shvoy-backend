-- PO-issuance gate: incoterms (required at generation), the advisory contract
-- reference + its persistent pending flag, the informational delivery address /
-- budget code, and the compliance-pending advisory flag (stamped at generation
-- from the supplier's compliance status). The two *_pending flags are advisory —
-- they never block; they're the dashboard-visible loose ends.
ALTER TABLE purchase_orders ADD COLUMN incoterms VARCHAR(10);
ALTER TABLE purchase_orders ADD COLUMN contract_reference VARCHAR(255);
ALTER TABLE purchase_orders ADD COLUMN delivery_address VARCHAR(500);
ALTER TABLE purchase_orders ADD COLUMN budget_code VARCHAR(100);
ALTER TABLE purchase_orders ADD COLUMN contract_pending BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE purchase_orders ADD COLUMN compliance_pending BOOLEAN NOT NULL DEFAULT FALSE;
