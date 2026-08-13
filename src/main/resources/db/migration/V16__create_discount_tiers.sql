CREATE TABLE discount_tiers (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    sku_price_id UUID NOT NULL REFERENCES sku_prices (id),
    quantity_threshold INTEGER NOT NULL,
    unit_price_amount NUMERIC(19, 4) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX idx_discount_tiers_price_threshold ON discount_tiers (company_id, sku_price_id, quantity_threshold);
CREATE INDEX idx_discount_tiers_company_id ON discount_tiers (company_id);
