-- Receipt rollup & PO closure: the PO-level surface flag for over-delivery
-- (cumulative received > ordered on at least one SKU). Interim, pending the
-- over-delivery rule; per-SKU detail is derived at read time in the rollup view,
-- never stored (the drift rule). Closure itself needs no column — CLOSED /
-- CLOSED_SHORT are new values of the existing VARCHAR status.
ALTER TABLE purchase_orders ADD COLUMN over_delivered BOOLEAN DEFAULT FALSE;
UPDATE purchase_orders SET over_delivered = FALSE WHERE over_delivered IS NULL;
ALTER TABLE purchase_orders ALTER COLUMN over_delivered SET NOT NULL;
