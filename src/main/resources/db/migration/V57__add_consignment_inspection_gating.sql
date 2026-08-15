-- Story 7.4 (revised): inspection-aware GRN gating. inspection_due is the flag
-- that makes the inspection report mandatory (a manual MVP flag applying the
-- Product Risk x Factory Performance cadence by hand; a scoring engine sets the
-- same flag later — the flag is the interface). Never set = not due = the no-QC-
-- service path, with zero inspection friction. grn_provenance records how the
-- GRN came to be (clean / inspection_not_due / qc_failed), set at creation.
ALTER TABLE shipment_consignments ADD COLUMN inspection_due BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shipment_consignments ADD COLUMN grn_provenance VARCHAR(30);
