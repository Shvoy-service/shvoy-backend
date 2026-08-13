ALTER TABLE purchase_order_lines ADD COLUMN price_found BOOLEAN;
ALTER TABLE purchase_order_lines ADD COLUMN priced_as_of_date DATE;
ALTER TABLE purchase_order_lines ADD COLUMN carton_valid BOOLEAN;
ALTER TABLE purchase_order_lines ADD COLUMN adjusted_quantity INTEGER;
