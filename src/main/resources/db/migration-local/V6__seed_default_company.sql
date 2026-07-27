-- Local-dev-only seed, so tenancy.local.default-company-id points at a real
-- row. Only applied under the local profile (see application-local.yml) —
-- never runs against dev/prod.
--
-- Must sort after V5 (which adds companies.name/created_at) since Flyway
-- orders all active locations together by version number — this was
-- originally V4 and got bumped past V5 for exactly that reason. If your
-- local Postgres volume has an older version of this file applied, run
-- `docker compose down -v` to recreate it.
INSERT INTO companies (id, name, created_at) VALUES
    ('00000000-0000-0000-0000-000000000001', 'Local Dev Co', now());
