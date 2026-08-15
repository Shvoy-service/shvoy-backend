-- Story 6.6, path (c): an accepted-as-is override force-passes the payment to
-- READY_TO_PAY despite the mismatch. The flag protects that human decision from
-- a later match re-run re-blocking it (same protection PAID/ON_HOLD already get).
ALTER TABLE payments ADD COLUMN match_overridden BOOLEAN NOT NULL DEFAULT FALSE;
