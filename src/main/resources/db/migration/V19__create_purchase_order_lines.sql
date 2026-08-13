CREATE TABLE purchase_order_lines (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders (id),
    sku_id UUID NOT NULL REFERENCES skus (id),
    line_number INTEGER NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price_amount NUMERIC(19, 4),
    currency VARCHAR(3),
    applied_tier_threshold INTEGER,
    line_total_amount NUMERIC(19, 2),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_purchase_order_lines_company_id ON purchase_order_lines (company_id);
CREATE INDEX idx_purchase_order_lines_purchase_order_id ON purchase_order_lines (purchase_order_id);
