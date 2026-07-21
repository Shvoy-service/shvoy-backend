-- Local-dev-only seed, so tenancy.local.default-company-id points at a real
-- row. Only applied under the local profile (see application-local.yml) —
-- never runs against dev/prod.
INSERT INTO companies (id) VALUES ('00000000-0000-0000-0000-000000000001');
