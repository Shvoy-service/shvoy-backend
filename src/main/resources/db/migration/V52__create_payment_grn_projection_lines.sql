-- Story 6.5: a payments-local projection of the goods-received quantities.
-- The GRN lives in the shipments module; payments cannot pull it (shipments
-- already depends on payments via the anchor seam, so a reverse pull would make
-- the module graph cyclic). Instead payments projects the received quantities
-- from the ProvisionalGoodsReceiptEvent it consumes, so the three-way match can
-- read the GRN leg when any trigger fires (invoice logged, PI confirmed, ...).
-- Full-replaced per consignment each time the event is (re-)published.
CREATE TABLE payment_grn_projection_lines (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders (id),
    consignment_id UUID NOT NULL,
    sku_id UUID NOT NULL,
    received_quantity INTEGER NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_payment_grn_projection_lines_company_id ON payment_grn_projection_lines (company_id);
CREATE INDEX idx_payment_grn_projection_lines_purchase_order_id ON payment_grn_projection_lines (purchase_order_id);
