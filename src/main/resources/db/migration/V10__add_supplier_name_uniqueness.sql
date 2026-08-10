-- Plain (non-expression) multi-column unique index, not a functional index
-- on LOWER(name): H2 (the test profile's database) doesn't reliably support
-- Postgres expression indexes, and this repo has been burned by H2/Postgres
-- migration syntax mismatches before. The application-level duplicate check
-- (SupplierService, via findByNameIgnoreCase) is case-insensitive; this
-- index is a case-sensitive race-safety-net only, the same role the
-- users.email UNIQUE constraint plays for RegistrationService/InvitationService.
CREATE UNIQUE INDEX idx_suppliers_company_id_name ON suppliers (company_id, name);
