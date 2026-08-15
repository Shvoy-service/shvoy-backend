-- Story 7.2: the structured fields captured alongside each uploaded document.
-- BL reference/date + ex-factory date already live on `shipments` (7.1); the
-- packing list and inspection report each carry their own reference/date (and
-- the inspection its outcome) per consignment — the co-loading rule keeps these
-- per-portion, not per-shipment.
ALTER TABLE shipment_consignments ADD COLUMN packing_list_reference VARCHAR(100);
ALTER TABLE shipment_consignments ADD COLUMN packing_list_date DATE;
ALTER TABLE shipment_consignments ADD COLUMN inspection_report_reference VARCHAR(100);
ALTER TABLE shipment_consignments ADD COLUMN inspection_report_date DATE;
ALTER TABLE shipment_consignments ADD COLUMN inspection_report_outcome VARCHAR(50);
