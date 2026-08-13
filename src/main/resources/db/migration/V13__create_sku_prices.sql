CREATE TABLE sku_prices (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    sku_id UUID NOT NULL REFERENCES skus (id),
    unit_price_amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_sku_prices_company_id ON sku_prices (company_id);
CREATE INDEX idx_sku_prices_sku_id ON sku_prices (sku_id);
