CREATE TABLE purchase_order_price_overrides (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders (id),
    overridden_by UUID NOT NULL REFERENCES users (id),
    reason VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_purchase_order_price_overrides_company_id ON purchase_order_price_overrides (company_id);
CREATE INDEX idx_purchase_order_price_overrides_purchase_order_id ON purchase_order_price_overrides (purchase_order_id);
