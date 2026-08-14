CREATE TABLE purchase_order_sends (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders (id),
    sent_by UUID NOT NULL REFERENCES users (id),
    recipient_email VARCHAR(255) NOT NULL,
    document_s3_key VARCHAR(500) NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_purchase_order_sends_company_id ON purchase_order_sends (company_id);
CREATE INDEX idx_purchase_order_sends_purchase_order_id ON purchase_order_sends (purchase_order_id);
