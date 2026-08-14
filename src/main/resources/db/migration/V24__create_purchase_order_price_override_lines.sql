CREATE TABLE purchase_order_price_override_lines (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    override_id UUID NOT NULL REFERENCES purchase_order_price_overrides (id),
    purchase_order_line_id UUID NOT NULL REFERENCES purchase_order_lines (id),
    manual_price_amount NUMERIC(19, 4) NOT NULL,
    manual_price_currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_purchase_order_price_override_lines_company_id ON purchase_order_price_override_lines (company_id);
CREATE INDEX idx_purchase_order_price_override_lines_override_id ON purchase_order_price_override_lines (override_id);
