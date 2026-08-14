ALTER TABLE purchase_orders ADD COLUMN order_total_amount NUMERIC(19, 2);
ALTER TABLE purchase_orders ADD COLUMN currency VARCHAR(3);
ALTER TABLE purchase_orders ADD COLUMN deposit_amount NUMERIC(19, 2);
ALTER TABLE purchase_orders ADD COLUMN balance_amount NUMERIC(19, 2);
