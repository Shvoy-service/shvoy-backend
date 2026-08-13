-- Plain (non-expression) multi-column unique index, not a functional index
-- on LOWER(code) — same reasoning as V10's supplier name index: H2 (the
-- test profile's database) doesn't reliably support Postgres expression
-- indexes. The application-level duplicate check (SkuService) is
-- case-insensitive; this index is a case-sensitive race-safety-net only.
CREATE UNIQUE INDEX idx_skus_company_id_supplier_id_code ON skus (company_id, supplier_id, code);
