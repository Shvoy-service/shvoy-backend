CREATE TABLE payments (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders (id),
    type VARCHAR(20) NOT NULL,
    amount_amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    due_date DATE,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_payments_company_id ON payments (company_id);
CREATE INDEX idx_payments_purchase_order_id ON payments (purchase_order_id);
