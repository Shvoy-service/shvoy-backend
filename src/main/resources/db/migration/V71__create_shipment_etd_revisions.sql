-- Story 7.5 — ETD tracking. Every change to a consignment's confirmed ETD is
-- kept as history (ETDs slip; revisions are the norm). The current value is the
-- latest revision, also mirrored on shipment_consignments.confirmed_etd for
-- easy read; this table is the trail — precisely what Phase 2's proactive
-- chasing and any supplier-reliability picture will read. The reason is optional
-- (demanding one for every routine slip is friction; the field lets meaningful
-- ones be recorded). No anchor, no payment interaction — ETD is not an anchor.
CREATE TABLE shipment_etd_revisions (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies (id),
    consignment_id UUID NOT NULL REFERENCES shipment_consignments (id),
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders (id),
    confirmed_etd DATE NOT NULL,
    reason VARCHAR(500),
    changed_by UUID NOT NULL REFERENCES users (id),
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_shipment_etd_revisions_company_id ON shipment_etd_revisions (company_id);
CREATE INDEX idx_shipment_etd_revisions_consignment_id ON shipment_etd_revisions (consignment_id);
