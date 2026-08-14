ALTER TABLE purchase_orders ADD COLUMN generated_by UUID REFERENCES users (id);
ALTER TABLE purchase_orders ADD COLUMN generated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE purchase_orders ADD COLUMN document_s3_key VARCHAR(500);
