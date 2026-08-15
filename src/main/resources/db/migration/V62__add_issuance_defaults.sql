-- PO-issuance gate: a per-supplier default incoterm (pre-filled onto a new PO,
-- editable per order — the pragmatic per-supplier-with-override default) and a
-- single default delivery address on the company (pre-filled onto a new PO; no
-- multi-site management).
ALTER TABLE suppliers ADD COLUMN default_incoterms VARCHAR(10);
ALTER TABLE companies ADD COLUMN default_delivery_address VARCHAR(500);
