-- Story 6.8 — payment release. The Pay action records a paid date (defaults to
-- the action date, overridable) and an optional free-text payment reference
-- (bank ref / batch id). SHVOY records the payment decision; it never moves
-- money — these are the record of a decision, not a transfer. Hold / release
-- reasons live on the immutable payment audit trail (like the 6.6 override
-- reason), so no columns for those.
ALTER TABLE payments ADD COLUMN paid_date DATE;
ALTER TABLE payments ADD COLUMN payment_reference VARCHAR(200);
